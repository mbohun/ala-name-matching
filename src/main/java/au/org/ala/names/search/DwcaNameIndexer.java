/*
 * Copyright (C) 2014 Atlas of Living Australia
 * All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 */
package au.org.ala.names.search;

import au.org.ala.names.lucene.analyzer.LowerCaseKeywordAnalyzer;
import au.org.ala.names.model.LinnaeanRankClassification;
import au.org.ala.names.model.NameIndexField;
import au.org.ala.names.model.NameSearchResult;
import au.org.ala.names.model.RankType;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.gbif.dwc.record.DarwinCoreRecord;
import org.gbif.dwc.text.Archive;
import org.gbif.dwc.text.ArchiveFactory;
import org.gbif.ecat.model.ParsedName;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

/**
 *
 * Create a name index from a DWCA.  All the required names should exist in the supplied DWCA.
 *
 * The indexer will create a temporary index in order to generate the higher level classification and nested sets for
 * the hierarchy. The main method uses the following options:
 *
 * <ul>
 *     <li>load - flag to indicate that only the load index should be created</li>
 *     <li>search - flag to indicate that only the search index should be created</li>
 *     <li>all - flag to indicate that both the load and search index should be created</li>
 *     <li>irmng - optional param that is used to specify where the IRMNG dwca archive is located.  When this is NOT provided no homonym index is created.</li>
 *     <li>dwca - compulsory param that provides the unzipped location of the DWCA for the scientific names</li>
 *     <li>target - optional param to provide the target location for the name matching index. This value will default to /data/namematching when not provided</li>
 *     <li>tmp - optional param to provide the location for the temporary index. This value will default to /data/tmp/lucene/nmload when not provided</li>
 *     <li>common - optional param to specify when the common name CSV file is located.  When this is NOT provided no common name index is created.</li>
 * </ul>
 *
 * @author Natasha Quimby (natasha.quimby@csiro.au)
 */
public class DwcaNameIndexer extends ALANameIndexer {

    static protected Log log = LogFactory.getLog(DwcaNameIndexer.class);
    private IndexSearcher lsearcher;
    private IndexWriter writer;
    private String dirTmpIndex;

    /**
     * Creates the name matching index based on a complete list of names supplied in a single DwCA
     *
     * @param loadingIndex True when the loading index should be created. This is necessary to generate the index, but you may wish to skip this step if it has be generated earlier
     * @param sciIndex True when the name matching index should be generated
     * @param indexDirectory The directory in which to create the name matching index
     * @param tmpLoadIndex The directory in which to create the temporary loading index
     * @param namesDwc The absolute path to the directory that contains the unzipped DWC archive to index
     * @param irmngDwc The absolute path to the directory that contains the unzipped IRMNG DWCA
     * @param commonNameFile
     * @throws Exception
     */
    public void create(boolean loadingIndex, boolean sciIndex, String indexDirectory, String tmpLoadIndex, String namesDwc, String irmngDwc, String commonNameFile) throws Exception{
        dirTmpIndex = tmpLoadIndex;
        LowerCaseKeywordAnalyzer analyzer = new LowerCaseKeywordAnalyzer();
        if(loadingIndex){
            createLoadingIndex(tmpLoadIndex, namesDwc);
        }
        if(sciIndex){
            writer = createIndexWriter(new File(indexDirectory + File.separator + "cb"), analyzer,true);
            generateIndex();
            addSynonymsToIndex(namesDwc);
            writer.commit();
            writer.forceMerge(1);
            writer.close();
        }
        if(irmngDwc != null && new File(irmngDwc).exists()){
            IndexWriter irmngWriter = createIndexWriter(new File(indexDirectory + File.separator + "irmng"), analyzer, true);
            this.indexIrmngDwcA(irmngWriter,irmngDwc);
            irmngWriter.forceMerge(1);
            irmngWriter.close();
        }
        if(commonNameFile != null && new File(commonNameFile).exists()){
            //index the common names
            indexCommonNames(createIndexWriter(new File(indexDirectory + File.separator + "vernacular"), new KeywordAnalyzer(),true),commonNameFile);
        }
    }

    /**
     * Index the common names CSV file supplied.
     *
     * CSV header need to be txaonId, taxonLsid, scientificName, vernacularName, languageCode, countryCode
     *
     * The languageCode and countryCode are not necessary as they are not used.
     *
     * @param iw
     * @param file
     * @throws Exception
     */
    private void indexCommonNames(IndexWriter iw, String file) throws Exception{
        //assumes that the quoted TSV file is in the following format
        //taxon id, taxon lsid, scientific name, vernacular name, language code, country code
        log.info("Starting to load the common names");
        int i =0, count=0;
        au.com.bytecode.opencsv.CSVReader cbreader = new au.com.bytecode.opencsv.CSVReader(new FileReader(file), '\t', '"', '\\', 0);
        for (String[] values = cbreader.readNext(); values != null; values = cbreader.readNext()) {
            i++;
            if(values.length == 6){
                //relies on having the same lsid supplied as the DWCA file
                String lsid = StringUtils.isNotEmpty(values[1]) ? values[1] : values[0];
                //check to see if it exists
                TopDocs result = getLoadIdxResults("lsid", lsid, 1);
                if(result.totalHits>0){
                    //we can add the common name
                    Document doc = getCommonNameDocument(values[3], values[2], lsid, 1.0f, false);
                    iw.addDocument(doc);
                    count++;
                }
            } else {
                log.info("Issue on line " + i + "  " + values[0]);
            }
            if(i%1000 == 0){
                log.info("Finished processing " + i + " common names with " + count + " added to index ");
            }
        }
        log.info("Finished processing " + i + " common names with " + count + " added to index ");
        iw.commit();
        iw.forceMerge(1);
        iw.close();
    }

    /**
     * Creates a loading index to use to generate the hierarchy including the left right values.
     *
     * @param tmpIndexDir
     * @param archiveDirectory
     * @throws Exception
     */
    private void createLoadingIndex(String tmpIndexDir,String archiveDirectory) throws Exception{
        log.info("Starting to create the temporary loading index.");
        File indexDir = new File(tmpIndexDir);
        IndexWriter iw = createIndexWriter(indexDir, new KeywordAnalyzer(), true);
        //create the loading index so that left right values and classifications can be generated
        Archive archive = ArchiveFactory.openArchive(new File(archiveDirectory));
        Iterator<DarwinCoreRecord> it = archive.iteratorDwc();
        int i=0;
        long start=System.currentTimeMillis();
        while(it.hasNext()){
            Document doc = new Document();
            DarwinCoreRecord dwcr = it.next();
            String id = dwcr.getId();
            String lsid = dwcr.getTaxonID() == null ? id : dwcr.getTaxonID();
            String acceptedLsid = dwcr.getAcceptedNameUsageID();
            //add and store the identifier for the record
            doc.add(new StringField(NameIndexField.ID.toString(), dwcr.getId(), Field.Store.YES));
            if(StringUtils.isNotBlank(lsid)){
                doc.add(new StringField(NameIndexField.LSID.toString(), lsid, Field.Store.YES));
            } else {
                System.out.println("LSID is null for " + id + " " + lsid + " " + lsid + " " + acceptedLsid);
            }
            if(StringUtils.isNotBlank(dwcr.getParentNameUsageID())) {
                doc.add(new StringField("parent_id", dwcr.getParentNameUsageID(), Field.Store.YES));
            }
            if(StringUtils.isNotBlank(dwcr.getAcceptedNameUsageID())) {
                doc.add(new StringField(NameIndexField.ACCEPTED.toString(),dwcr.getAcceptedNameUsageID(), Field.Store.YES));
            }
            if(StringUtils.isNotBlank(dwcr.getScientificName())) {
                //stored no need to search on
                doc.add(new StoredField(NameIndexField.NAME.toString(),dwcr.getScientificName()));
            }
            if(StringUtils.isNotBlank(dwcr.getScientificNameAuthorship())) {
                //stored no need to search on
                doc.add(new StoredField(NameIndexField.AUTHOR.toString(),dwcr.getScientificNameAuthorship()));
            }
            if(StringUtils.isNotBlank(dwcr.getGenus())) {
                //stored no need to search on
                doc.add(new StoredField("genus",dwcr.getGenus()));
            }
            if(StringUtils.isNotBlank(dwcr.getSpecificEpithet())) {
                //stored no need to search on
                doc.add(new StoredField(NameIndexField.SPECIFIC.toString(),dwcr.getSpecificEpithet()));
            }
            if(StringUtils.isNotBlank(dwcr.getInfraspecificEpithet())) {
                //stored no need to search on
                doc.add(new StoredField(NameIndexField.INFRA_SPECIFIC.toString(),dwcr.getInfraspecificEpithet()));
            }
            if(StringUtils.isNotBlank(dwcr.getTaxonRank())){
                //match the supplied rank
                RankType rt = RankType.getForStrRank(dwcr.getTaxonRank());
                if(rt != null){
                    doc.add(new StringField(NameIndexField.RANK.toString(), rt.getRank(), Field.Store.YES));
                    doc.add(new StringField(NameIndexField.RANK_ID.toString(), rt.getId().toString(), Field.Store.YES));
                } else {
                    doc.add(new StringField(NameIndexField.RANK.toString(), dwcr.getTaxonRank(), Field.Store.YES));
                    doc.add(new StringField(NameIndexField.RANK_ID.toString(), RankType.UNRANKED.getId().toString(), Field.Store.YES));
                }
            } else {
                //put in unknown rank
                doc.add(new StringField(NameIndexField.RANK.toString(), "Unknown", Field.Store.YES));
                doc.add(new StringField(NameIndexField.RANK_ID.toString(), RankType.UNRANKED.getId().toString(), Field.Store.YES));
            }
            if(StringUtils.equals(lsid, acceptedLsid) || StringUtils.equals(id, acceptedLsid) || acceptedLsid == null){
                //mark this one as an accepted concept
                doc.add(new StringField(NameIndexField.iS_SYNONYM.toString(),"F", Field.Store.YES));
                if (StringUtils.isBlank(dwcr.getParentNameUsageID())){
                    doc.add(new StringField("root","T", Field.Store.YES));
                }
            } else {
                doc.add(new StringField(NameIndexField.iS_SYNONYM.toString(),"T", Field.Store.YES));
            }
            iw.addDocument(doc);
            i++;
            if(i % 1000 == 0){
                long finish = System.currentTimeMillis();
                log.debug("Loading index: " + i + " records per sec: " + (1000/(((float)(finish/start))/1000)));
                start =finish;
            }
        }
        log.info("Finished creating the temporary load index with " + i + " concepts");
        iw.commit();
        iw.forceMerge(1);
        iw.close();
        lsearcher =new IndexSearcher(DirectoryReader.open(FSDirectory.open(indexDir)));
    }

    private TopDocs getLoadIdxResults(String field, String value,int max) throws Exception {
        if(lsearcher == null && new File(dirTmpIndex).exists()) {
            lsearcher = new IndexSearcher(DirectoryReader.open(FSDirectory.open(new File(dirTmpIndex))));
        } else if(lsearcher == null && !new File(dirTmpIndex).exists()){
            throw new RuntimeException("A load index has not been generated. Please run this tool with '-load' before creating the search index.");
        }
        TermQuery tq = new TermQuery(new Term(field, value));
        return lsearcher.search(tq,max);
    }

    /**
     * generates the accepted concepts for the name matching index.
     *
     * Relies on the loading indexing being created
     *
     * @throws Exception
     */
    private void generateIndex() throws Exception{
        //get all the records that don't have parents that are accepted
        TopDocs rootConcepts = getLoadIdxResults("root","T", 25000);
        int left = 0;
        int right = left;
        for(ScoreDoc sd : rootConcepts.scoreDocs){
            left = right + 1;
            Document doc =lsearcher.doc(sd.doc);
            right = addIndex(doc, 1, left,new LinnaeanRankClassification());
            log.info("Finished loading "+ doc.get(NameIndexField.LSID.toString()) + " "  + doc.get(NameIndexField.NAME.toString()) + " " + left + " " + right);
        }
    }

    /**
     * Adds a document to the name matching index after populating the hierarchy
     * @param doc
     * @param currentDepth
     * @param currentLeft
     * @param higherClass
     * @return
     * @throws Exception
     */
    private int addIndex(Document doc,int currentDepth,int currentLeft, LinnaeanRankClassification higherClass ) throws Exception {
        String id = doc.get(NameIndexField.ID.toString());
        //get children for this record
        TopDocs children = getLoadIdxResults("parent_id", id, 25000);
        if(children.totalHits == 0){
            children = getLoadIdxResults("parent_id", doc.get(NameIndexField.LSID.toString()), 25000);
        }
        int left = currentLeft;
        int right = left;
        int rankId = Integer.parseInt(doc.get(NameIndexField.RANK_ID.toString()));
        String name = doc.get(NameIndexField.NAME.toString());
        String lsid = doc.get(NameIndexField.LSID.toString());
        //get the canonical version if the sciname
        String cname = getCanonical(name);
        //create a new classification for this entry based on the parent
        LinnaeanRankClassification newcl = new LinnaeanRankClassification(higherClass);
        switch(rankId){
            case 1000:
                newcl.setKingdom(cname);
                newcl.setKid(lsid);
                break;
            case 2000:
                newcl.setPhylum(cname);
                newcl.setPid(lsid);
                break;
            case 3000:
                newcl.setKlass(cname);
                newcl.setCid(lsid);
                break;
            case 4000:
                newcl.setOrder(cname);
                newcl.setOid(lsid);
                break;
            case 5000:
                newcl.setFamily(cname);
                newcl.setFid(lsid);
                break;
            case 6000:
                newcl.setGenus(cname);
                newcl.setGid(lsid);
                break;
            case 7000:
                newcl.setSpecies(cname);
                newcl.setSid(lsid);
                break;
        }
        for(ScoreDoc child : children.scoreDocs){
            Document cdoc = lsearcher.doc(child.doc);
            //child, currentDepth + 1, right + 1, map.toMap, dao)
            right = addIndex(cdoc, currentDepth +1, right+1, newcl);
        }
        if(left % 2000 == 0){
            log.debug("Last processed lft:" + left + " rgt:" + right + " depth:" + currentDepth + " classification " + newcl );
        }
        //now insert this term
        Document indexDoc = this.createALAIndexDocument(cname, doc.get(NameIndexField.ID.toString()), lsid, doc.get(NameIndexField.AUTHOR.toString()),doc.get(NameIndexField.RANK.toString()),doc.get(NameIndexField.RANK_ID.toString()), Integer.toString(left), Integer.toString(right), newcl);
        writer.addDocument(indexDoc);
        return right + 1;
    }

    /**
     *
     * @param name
     * @return The canonical form of the supplied name.
     */
    private String getCanonical(String name){
        try {
            ParsedName pn = parser.parse(name);
            if(pn.isParsableType()){
                return pn.canonicalName();
            }
        } catch(Exception e){
            //do nothing
        }
        return name;
    }

    /**
     * Adds the synonyms to the indexed based on the dwca.  A synonym is where the id, lsid is different to the accepted lsid
     * @param dwcaDir
     */
    private void addSynonymsToIndex(String dwcaDir) throws Exception {
        Archive archive = ArchiveFactory.openArchive(new File(dwcaDir));
        Iterator<DarwinCoreRecord> it = archive.iteratorDwc();
        int i = 0;
        int count = 0;
        while(it.hasNext()){
            DarwinCoreRecord dwcr = it.next();
            i++;
            String lsid = dwcr.getTaxonID() != null ? dwcr.getTaxonID() : dwcr.getId();
            String id = dwcr.getId();
            String acceptedId = dwcr.getAcceptedNameUsageID();
            if(StringUtils.isNotEmpty(acceptedId) && (!StringUtils.equals(acceptedId , id) && !StringUtils.equals(acceptedId, lsid))){
                count++;
                //we have a synonym that needs to be load
                writer.addDocument(this.createALASynonymDocument(dwcr.getScientificName(), dwcr.getScientificNameAuthorship(), dwcr.getId(), lsid, lsid, dwcr.getAcceptedNameUsageID(), dwcr.getAcceptedNameUsageID(), 1.0f, dwcr.getTaxonomicStatus()));
            }
            if(i % 1000 == 0){
                log.debug("Processed " + i + " records " + count + " synonyms" );
            }
        }
    }

    /**
     * Example run
     *
     * java –cp .:names.jar au.org.ala.checklist.lucene.DwcaNameIndexer
     * -all
     * -dwca /data/bie-staging/names-lists/dwca-col
     * -target /data/lucene/testdwc-namematching
     * -irmng /data/bie-staging/irmng/IRMNG_DWC_HOMONYMS
     * -common /data/bie-staging/ala-names/col_vernacular.txt
     *
     * @param args
     */
    public static void main(String[] args) {

        final String DEFAULT_DWCA = "/data/lucene/sources/dwca-col";
        final String DEFAULT_IRMNG = "/data/lucene/sources/IRMNG_DWC_HOMONYMS";
        final String DEFAULT_COMMON_NAME = "/data/lucene/sources/col_vernacular.txt";
        final String DEFAULT_TARGET_DIR = "/data/lucene/namematching";
        final String DEFAULT_TMP_DIR = "/data/lucene/nmload-tmp";

        Options options = new Options();
        options.addOption("v", "version", false, "Retrieve version information");
        options.addOption("h", "help", false, "Retrieve options");
        options.addOption("all", false, "Generates the load index and search index");
        options.addOption("load", false, "Generate the load index only. " +
                "The load index is a temporary index generated from the raw data files" +
                " used to load the main search index");
        options.addOption("search", false, "Generates the search index. A load index must already be created for this to run.");
        options.addOption("irmng", true, "The absolute path to the unzipped irmng DwCA. IRMNG is used to detect homonyms. Defaults to " + DEFAULT_IRMNG);
        options.addOption("dwca", true, "The absolute path to the unzipped DwCA for the scientific names. Defaults to " + DEFAULT_DWCA);
        options.addOption("target", true, "The target directory to write the new name index to. Defaults to " + DEFAULT_TARGET_DIR);
        options.addOption("tmp", true, "The tmp directory for the load index. Defaults to " + DEFAULT_TMP_DIR);
        options.addOption("common", true, "The common (vernacular) name file. Defaults to " + DEFAULT_COMMON_NAME);
        options.addOption("testSearch", true, "Debug a name search. This uses the target directory to search against.");

        CommandLineParser parser = new BasicParser();

        try {
            // parse the command line arguments
            CommandLine line = parser.parse(options, args);

            if (line.hasOption("v")){
                //only load the properties file if it exists otherwise default to the biocache-test-config.properties on the classpath
                InputStream stream = DwcaNameIndexer.class.getResourceAsStream("/git.properties");
                Properties properties = new Properties();
                if(stream != null){
                    properties.load(stream);
                    properties.list(System.out);
                } else {
                    System.err.println("Unable to retrieve versioning information");
                }
                System.exit(-1);
            }

            if (line.hasOption("help")){
                //only load the properties file if it exists otherwise default to the biocache-test-config.properties on the classpath
                new HelpFormatter().printHelp("nameindexer", options);
                System.exit(-1);
            }

            if (line.hasOption("testSearch")){

                boolean indexExists = (new File(DEFAULT_TARGET_DIR).exists());

                if(indexExists) {
                    //do a name search - with option flag pointing to index location
                    System.out.println("Search for name");
                    ALANameSearcher searcher = new ALANameSearcher(line.getOptionValue("target", DEFAULT_TARGET_DIR));
                    NameSearchResult nsr = searcher.searchForRecord(line.getOptionValue("testSearch"));
                    if(nsr != null) {
                        Map<String, String> props = nsr.toMap();
                        for (Map.Entry<String, String> entry : props.entrySet()) {
                            System.out.println(entry.getKey() + ": " + entry.getValue());
                        }
                    } else {
                        System.err.println("No match for " + line.getOptionValue("testSearch"));
                    }
                } else {
                    System.err.println("Index unreadable. Check " + DEFAULT_TARGET_DIR);
                }
                System.exit(-1);
            }

            boolean load = line.hasOption("load") || line.hasOption("all");
            boolean search = line.hasOption("search") || line.hasOption("all");

            if(!line.hasOption("load") && !line.hasOption("search") && !line.hasOption("all") ){
                load = true;
                search = true;
            }

            log.info("Generating loading index: " + load);
            log.info("Generating searching index: " + search);

            boolean defaultIrmngReadable = (new File(DEFAULT_IRMNG).exists());
            boolean defaultCommonReadable = (new File(DEFAULT_COMMON_NAME).exists());
            boolean defaultDwcaReadable = (new File(DEFAULT_DWCA).exists());

            if(line.getOptionValue("dwca") != null){
                log.info("Using the  DwCA name file: " + line.getOptionValue("dwca"));
            } else if (defaultDwcaReadable){
                log.info("Using the default DwCA name file: " + DEFAULT_DWCA);
            } else {
                log.error("No DwC Archive specified and the default file path does not exist or is inaccessible. Default path: " + DEFAULT_DWCA);
                System.exit(-1);
            }

            if(line.getOptionValue("irmng") == null && !defaultIrmngReadable){
                log.warn("No IRMNG export specified and the default file path does not exist or is inaccessible. Default path: " + DEFAULT_IRMNG);
            } else {
                log.info("Using the default IRMNG name file: " + DEFAULT_IRMNG);
            }

            if(line.getOptionValue("common") == null && !defaultCommonReadable) {
                log.warn("No common name export specified and the default file path does not exist or is inaccessible. Default path: " + DEFAULT_COMMON_NAME);
            } else {
                log.info("Using the default common name file: " + DEFAULT_COMMON_NAME);
            }

            File targetDirectory = new File(line.getOptionValue("target", DEFAULT_TARGET_DIR));
            if(targetDirectory.exists()){
                String newPath =  targetDirectory.getAbsolutePath() + "_" + DateFormatUtils.format(new Date(), "yyyy-MM-dd_hh-mm-ss");
                log.info("Target directory already exists. Backing up to : " + newPath);
                File newTargetDirectory = new File(newPath);
                FileUtils.moveDirectory(targetDirectory, newTargetDirectory);
                FileUtils.forceMkdir(targetDirectory);
            }

            DwcaNameIndexer indexer = new DwcaNameIndexer();
            indexer.create(
                    load,
                    search,
                    line.getOptionValue("target", DEFAULT_TARGET_DIR),
                    line.getOptionValue("tmp", DEFAULT_TMP_DIR),
                    line.getOptionValue("dwca", DEFAULT_DWCA),
                    line.getOptionValue("irmng", DEFAULT_IRMNG),
                    line.getOptionValue("common", DEFAULT_COMMON_NAME)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
