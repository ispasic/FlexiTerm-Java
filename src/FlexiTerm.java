/* ====================================================================
 * Copyright (c) 2013, Dr Irena Spasic
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met: 
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution. 
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are those
 * of the authors and should not be interpreted as representing official policies, 
 * either expressed or implied, of the FreeBSD Project.
 * ==================================================================== */

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.StringReader;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.sql.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.DecimalFormat;


import java.util.*;
import java.io.*;
import java.text.*;


// --- import Stanford NLP classes
import edu.stanford.nlp.ling.Sentence;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.WordTag;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import edu.stanford.nlp.process.Morphology;
import edu.stanford.nlp.ling.WordLemmaTag;

// --- import WordNet classes
import edu.mit.jwi.*;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;
import edu.mit.jwi.morph.SimpleStemmer;

// --- import Jazzy (spell checker) classes
import com.swabunga.spell.engine.SpellDictionary;
import com.swabunga.spell.engine.SpellDictionaryHashMap;
import com.swabunga.spell.event.SpellCheckEvent;
import com.swabunga.spell.event.SpellCheckListener;
import com.swabunga.spell.event.SpellChecker;
import com.swabunga.spell.event.StringWordTokenizer;

// --- import TinyLogger
import org.pmw.tinylog.Logger;

// --- import MinorThird classes
import edu.cmu.minorthird.text.mixup.Mixup;
import edu.cmu.minorthird.text.mixup.MixupInterpreter;
import edu.cmu.minorthird.text.mixup.MixupProgram;

import edu.cmu.minorthird.text.NestedTextLabels;
import edu.cmu.minorthird.text.TextLabels;
import edu.cmu.minorthird.text.TextLabelsLoader;
import edu.cmu.minorthird.util.CommandLineProcessor;
import edu.cmu.minorthird.ui.CommandLineUtil;
import edu.cmu.minorthird.util.JointCommandLineProcessor;



public class FlexiTerm {

// --- (global) database connection variables ---
private static Connection con;
private static String driver = "org.sqlite.JDBC";
private static String url = "jdbc:sqlite::memory:";

// --- linguistic pre-processing ---
private static MaxentTagger tagger;
private static WordNet wordnet;
private static Porter porter = new Porter();

// --- settings (default values) ---
private static String pattern = 
    "(((((NN|JJ) )*NN) IN (((NN|JJ) )*NN))|((NN|JJ )*NN POS (NN|JJ )*NN))|(((NN|JJ) )+NN)";
                                                              // --- POS pattern for term candidate selection
private static String stoplist = "../resources/stoplist.txt";            // --- stoplist location - modify the file content if necessary
private static int    max = 3;                                // --- jazzy distance treshold: How many operations away? - reduce for better similarity
private static int    min = 2;                                // --- term frequency threshold: occurrence > min - increase for better precision
private static int    MIN = 9;                                // --- implicit acronym frequency threshold: occurrence > min - increase for better precision
private static String acronyms = "explicit";                  // --- acronym expansion mode: 
                                                              //     (1) explicit = explicitly defined in text using parentheses, e.g. scientific articles
                                                              //     (2) implicit = frequently used, but not explicitly introduced, e.g. clinical notes
                                                              //     (3) none     = no attempt to expand acronyms
private static int profiling = 0;

public static void main(String[] args)
{
  try
  {


    // --- load settings
    loadSettings("../config/settings.txt");

    open();  // --- SQLite database connection

    Statement stmt  = con.createStatement();
    Statement stmt1 = con.createStatement();
    ResultSet rs, rs1;
    String query;

    stmt.execute("PRAGMA journal_mode = OFF;");

    query = "CREATE TABLE data_document ( id VARCHAR(30), document TEXT, verbatim text, PRIMARY KEY(id) );"; stmt.execute(query);
    query = "CREATE TABLE data_sentence ( id VARCHAR(50) PRIMARY KEY , doc_id VARCHAR(30), position INT, sentence TEXT, tagged_sentence TEXT, tags TEXT);"; stmt.execute(query);
    query = "CREATE TABLE data_token ( sentence_id VARCHAR(50), position INT, token VARCHAR(30), lower_token VARCHAR(30), stem VARCHAR(30), lemma VARCHAR(30), tag VARCHAR(10), gtag VARCHAR(10), wntag CHAR(1), FOREIGN KEY (sentence_id) REFERENCES data_sentence(id) );"; stmt.execute(query);
    query = "CREATE TABLE stopword ( word VARCHAR(30), PRIMARY KEY (word) );"; stmt.execute(query);
    query = "CREATE TABLE term_acronym (acronym TEXT NOT NULL check(typeof(acronym) = 'text') , \"definition\" TEXT check(typeof(\"definition\") = 'text') );"; stmt.execute(query);
    query = "CREATE TABLE term_bag ( id INT, token VARCHAR(30), FOREIGN KEY(id) REFERENCES term_normalised(rowid) );"; stmt.execute(query);
    query = "CREATE TABLE term_normalised ( normalised TEXT, expanded TEXT, len INT, PRIMARY KEY(normalised) );"; stmt.execute(query);
    query = "CREATE TABLE term_nested_aux ( parent int, child int, PRIMARY KEY(parent, child), FOREIGN KEY(parent) REFERENCES term_normalised(rowid), FOREIGN KEY(child) REFERENCES term_normalised(rowid) );"; stmt.execute(query);
    query = "CREATE TABLE term_nested ( parent TEXT, child TEXT, PRIMARY KEY(parent, child) );"; stmt.execute(query);
    
    query = "CREATE TABLE term_phrase ( id VARCHAR(60), sentence_id VARCHAR(50), token_start INT, token_length INT, phrase TEXT, normalised TEXT, PRIMARY KEY(id), FOREIGN KEY (sentence_id) REFERENCES data_sentence(id) );"; stmt.execute(query);
    query = "CREATE TABLE term_phrase_temp (normalised TEXT, phrase TEXT, lower_char_phrase TEXT, length_phrase INT, lower_ns_phrase TEXT);"; stmt.execute(query);
    query = "CREATE TABLE term_termhood (expanded TEXT PRIMARY KEY ,representative TEXT,len INT, f INT,\"s\" INT,\"nf\" INT,\"c\" REAL DEFAULT (null) );"; stmt.execute(query);
    query = "CREATE TABLE token ( token VARCHAR(30), PRIMARY KEY(token) );"; stmt.execute(query);
    query = "CREATE TABLE token_similarity ( token1 VARCHAR(30), token2 VARCHAR(30), PRIMARY KEY(token1, token2), FOREIGN KEY (token1) REFERENCES token(token), FOREIGN KEY (token2) REFERENCES token(token) );"; stmt.execute(query);
    query = "CREATE TABLE output_html ( id VARCHAR(30), document TEXT, PRIMARY KEY(id) );"; stmt.execute(query);
    query = "CREATE TABLE output_label ( doc_id TEXT, start int, offset int, label int, primary key (doc_id, start, offset, label) );"; stmt.execute(query);
    query = "CREATE TABLE output_table ( id int, rank int NOT NULL, representative TEXT NOT NULL, variant TEXT NOT NULL, f int NOT NULL, c REAL NOT NULL, \"\", PRIMARY KEY(id, variant) );"; stmt.execute(query);
    query = "CREATE TABLE vector_space (stem TEXT NOT NULL, PRIMARY KEY(stem));"; stmt.execute(query);
    query = "CREATE TABLE vector_row ( id INT, term TEXT, vector TEXT, primary key(id) );"; stmt.execute(query);
    query = "CREATE TABLE vector_feature ( id INT, stem TEXT, d INT, w DOUBLE );"; stmt.execute(query);
    query = "CREATE TABLE vector_name ( id INT NOT NULL, term TEXT NOT NULL );"; stmt.execute(query);
    query = "CREATE TABLE tmp_acronym (acronym TEXT NOT NULL check(typeof(acronym) = 'text') , definition TEXT check(typeof(definition) = 'text') );"; stmt.execute(query);
    query = "CREATE TABLE tmp_normalised ( changefrom TEXT NOT NULL, changeto TEXT NOT NULL );"; stmt.execute(query);

    //Temporary tables
    query = "CREATE TABLE data_token_position ( lower_token VARCHAR(30), position INT);"; stmt.execute(query);
    query = "CREATE TABLE data_token_ordered ( token VARCHAR(30), position INT);"; stmt.execute(query);

    //Create indexes 
    query = "CREATE INDEX word_index ON stopword (word);"; stmt.execute(query);
    query = "CREATE INDEX lower_token_index ON data_token (sentence_id,position);"; stmt.execute(query);
    query = "CREATE INDEX term_bag_index ON term_bag (token,id);"; stmt.execute(query);
    query = "CREATE INDEX phrase_index ON term_phrase (phrase);"; stmt.execute(query);

    // --- import stoplist
    loadStoplist(stoplist);

    // --- load dictionaries & models
    tagger = new MaxentTagger("../resources/models/left3words-wsj-0-18.tagger");
    wordnet = new WordNet("../resources/dict");

    long loadDocsStartTime, loadDocsEndTime;
    double loadDocsRuntime;
    loadDocsStartTime = System.currentTimeMillis();

    // --- load & preprocess documents
    loadDocuments("../text");

    loadDocsEndTime = System.currentTimeMillis();
    loadDocsRuntime = (loadDocsEndTime-loadDocsStartTime) / 1000.0;

    // --- start timing term recognition
    long startTime = 0;
    long endTime;
    double runTime = 0;

    long partStartTime = 0;
    long partEndTime = 0;
    double [] partRuntimes = new double[10];

    if(profiling > 0)
    {
      startTime = System.currentTimeMillis();
      Logger.debug("Start time: " + ((startTime % 86400000) / 3600000 + 1) + ":" + (startTime % 3600000) / 60000 + ":" + (startTime % 60000) / 1000);
    }
   

    // ******************* EXTRACT NOUN PHRASES OF GIVEN STRUCTURE *******************
    if(profiling > 0)
    {	
       partStartTime = System.currentTimeMillis();
    }

    // --- for each sentence
    query = "SELECT id, tags FROM data_sentence ORDER BY id;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);

    // --- NP pattern filter - default: "(((((NN|JJ) )*NN) IN (((NN|JJ) )*NN))|((NN|JJ )*NN POS (NN|JJ )*NN))|(((NN|JJ) )+NN)"
    Pattern r = Pattern.compile(pattern);

    while (rs.next())
    {
      String sentence_id = rs.getString(1);  // --- sentence id
      String tags        = rs.getString(2);  // --- sentence tag pattern

      Matcher m = r.matcher(tags);

      //Fill 'data_token_position' table
      query = "INSERT INTO data_token_position (lower_token, position) "                                        + "\n" +
              "SELECT lower_token, position"                                                                     + "\n" +
              "FROM   data_token"                                                                                + "\n" +
              "WHERE  sentence_id = '" + sentence_id + "'"                                                       + "\n" +
              "AND    (token LIKE '%-%-%' OR token LIKE 'unit%' or token LIKE 'area%' or token LIKE 'history'"   + "\n" +
              "OR     (lower_token IN (SELECT LOWER(word) FROM stopword)));";
      Logger.debug(query);
      stmt1.execute(query);

      query = "INSERT INTO data_token_ordered (token, position) "                                        + "\n" +
              "SELECT token, position"                          + "\n" +
              "FROM   data_token"                               + "\n" +           
              "WHERE  sentence_id = '" + sentence_id + "'"      + "\n" + 
              "ORDER BY position ASC;";

      Logger.debug(query);
      stmt1.execute(query);


      // --- for each matching chunk
      while(m.find())
      {
        String chunk = m.group(0);                  // --- chunk tags
        String pre = tags.substring(0, m.start());  // --- the preceding tags

        int start  = whitespaces(pre)   + 1;        // --- start token position
        int length = whitespaces(chunk) + 1;        // --- chunk length in tokens


        // --- trim leading stop words
        while (length > 1)
        {

	  //Get tokens in lowercase
          query = "SELECT lower_token"                                                                      + "\n" +
                  "FROM   data_token_position"                                                              + "\n" +
                  "WHERE  position = " + start;  							     

          Logger.debug(query);
          rs1 = stmt1.executeQuery(query);

          if (rs1.next())
          {
            String stopword = rs1.getString(1);
            start++;
            length--;

          }
          else break;
          rs1.close();
        }

        // --- trim trailing stop words
        while (length > 1)
        {

          query = "SELECT T.lower_token"                                                   + "\n" +
                  "FROM   data_token T, stopword S"                                         + "\n" +
                  "WHERE  sentence_id = '" + sentence_id + "'"                              + "\n" +
                  "AND    position = " + (start + length - 1)                               + "\n" +
                  "AND    (T.lower_token = LOWER(S.word) OR T.gtag = 'JJ');"; 

          Logger.debug(query);
          rs1 = stmt1.executeQuery(query);


          if (rs1.next())
          {
            String stopword = rs1.getString(1);
            length--;
          }
          else break;
          rs1.close();
        }

        if (1 < length && length < 8) // --- if still multiword phrase and not too long
        {
          String phrase_id = sentence_id + "." + start;

          String phrase = "";

	  query = "SELECT position, token"                          + "\n" +
		  "FROM data_token_ordered"                         + "\n" +
		  "WHERE " + start + " <= position"                + "\n" +
                  "AND    position < " + (start + length); 

          Logger.debug(query);
          rs1 = stmt1.executeQuery(query);
          
	  while (rs1.next()) phrase += " " + rs1.getString(2);
          phrase = phrase.substring(1);
          int last = phrase.length() - 1;
          if (phrase.charAt(last) == '.') phrase = phrase.substring(0, last);
          rs1.close();

          // --- ignore phrases that contain web concepts: email address, URL, #hashtag
          if (!(phrase.contains("@") || phrase.contains("#") || phrase.toLowerCase().contains("http") || phrase.toLowerCase().contains("www")))
          {
            query = "INSERT INTO term_phrase(id, sentence_id, token_start, token_length, phrase)\n" +
                    "VALUES ('" + phrase_id + "', '" + sentence_id + "', " + start + ", " + length + ", " + fixApostrophe(phrase) + ");";
            Logger.debug(query);
            stmt1.execute(query);
          }
        }
      }

      //Empty temporary tables for next iteration

      query = "DELETE FROM data_token_position;";
      Logger.debug(query);
      stmt1.execute(query);

      query = "DELETE FROM data_token_ordered;";
      Logger.debug(query);
      stmt1.execute(query);

    }
    rs.close();

    if(profiling > 0)
    {
      partEndTime = System.currentTimeMillis();
      partRuntimes[0] = (partEndTime - partStartTime) / 1000.0;
    }
    // ******************* NORMALISE TERM CANDIDATES *******************

    if(profiling > 0)
    {
      partStartTime = System.currentTimeMillis();
    }

    // 1 --- remove punctuation, numbers and stop words
    // 2 --- remove any lowercase tokens shorter than 3 characters    LOWER(token) = token AND LENGTH(token) < 3
    // 3 --- stem & lowercase each remaining token                    SELECT LOWER(stem)
    // 4 --- sort tokens alphabetically

    query = "SELECT id, sentence_id, token_start, token_length FROM term_phrase;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);

    while (rs.next())
    {
      String phrase_id   = rs.getString(1);
      String sentence_id = rs.getString(2);
      int    start       = rs.getInt(3);
      int    length      = rs.getInt(4);

      String normalised = "";

      query = "SELECT DISTINCT LOWER(stem)"                            + "\n" +
              "FROM   data_token"                                      + "\n" +
              "WHERE  sentence_id = '" + sentence_id + "'"             + "\n" +
              "AND    " + start + " <= position"                       + "\n" +
              "AND    position < " + (start + length)                  + "\n" +
              "AND NOT (LOWER(token) = token AND LENGTH(token) < 3)"   + "\n" +
              "EXCEPT SELECT word FROM stopword"                       + "\n" +
              "ORDER BY LOWER(stem) ASC;";

      Logger.debug(query);
      rs1 = stmt1.executeQuery(query);

      while (rs1.next()) normalised += " " + rs1.getString(1);
      if (normalised.length() > 0) normalised = normalised.substring(1);
      normalised = normalised.replaceAll("\\.", "");   // --- e.g. U.K., Dr., St. -> UK, Dr, St
      Logger.debug(normalised);
      rs1.close();

      query = "UPDATE term_phrase"                                      + "\n" +
              "SET    normalised = " + fixApostrophe(normalised)        + "\n" +
              "WHERE  id = '" + phrase_id + "';";
      Logger.debug(query);
      stmt1.execute(query);
    }
    rs.close();

    // --- re-normalise term candidates that have different TOKENISATION,
    //     e.g. NF-kappa B vs. NF-kappaB
    //     or   nuclear factor-kappa B vs. nuclear factor-kappaB
    // --- keep the one with FEWER tokens (e.g. NF-kappaB)
    // --- this choice is better when expanding acronyms later on

    query = "INSERT INTO term_phrase_temp(normalised, phrase, lower_char_phrase, length_phrase, lower_ns_phrase)"     + "\n" +
	    "SELECT normalised, phrase, LOWER(SUBSTR(phrase, 1, 1)), LENGTH(phrase), LOWER(REPLACE(phrase, ' ', ''))" + "\n" +
            "FROM term_phrase"; stmt.execute(query);

    query = "DELETE FROM tmp_normalised;"; stmt.execute(query);

    query = "INSERT INTO tmp_normalised(changefrom, changeto)"                                  + "\n" +
            "SELECT DISTINCT P1.normalised, P2.normalised"                                      + "\n" +
            "FROM   term_phrase_temp P1, term_phrase_temp P2"                                   + "\n" +
            "WHERE  P1.phrase LIKE '% %'"                                                       + "\n" +
            "AND    P1.lower_char_phrase = P2.lower_char_phrase"                                + "\n" +
            "AND    P1.length_phrase > P2.length_phrase"                                        + "\n" +
            "AND    P1.lower_ns_phrase = P2.lower_ns_phrase"   + "\n";

    Logger.debug(query);
    stmt.execute(query);

    query = "SELECT changefrom, changeto FROM tmp_normalised;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next())
    {
      String changefrom = rs.getString(1);
      String changeto   = rs.getString(2);

      query = "UPDATE term_phrase SET normalised = " + fixApostrophe(changeto)           + "\n" +
              "WHERE                  normalised = " + fixApostrophe(changefrom) + ";"   + "\n";
      Logger.debug(query);
      stmt1.execute(query);
    }
    rs.close();

    // --- re-normalise term candidates that have different HYPHENATION,
    //     e.g. nuclear factor-kappa B vs. nuclear factor kappa B
    //     or   NF-kappa B vs. NF kappa B
    // --- keep the HYPHENATED form
    // --- this means FEWER tokens, so consistent with the above

    query = "DELETE FROM term_phrase_temp;"; stmt.execute(query);

    query = "INSERT INTO term_phrase_temp(normalised, phrase, lower_char_phrase, length_phrase, lower_ns_phrase)"     + "\n" +
	    "SELECT normalised, phrase, LOWER(SUBSTR(phrase, 1, 1)), LENGTH(phrase), LOWER(phrase)" + "\n" +
            "FROM term_phrase"; stmt.execute(query);

    query = "DELETE FROM tmp_normalised;"; stmt.execute(query);

    query = "INSERT INTO tmp_normalised(changefrom, changeto)"                             + "\n" +
            "SELECT P2.normalised, P1.normalised"                                          + "\n" +
            "FROM   term_phrase_temp P1, term_phrase_temp P2"                              + "\n" +
            "WHERE  P1.phrase LIKE '%-%'"                                                  + "\n" +
            "AND    P1.lower_char_phrase = P2.lower_char_phrase"                           + "\n" +
            "AND    P1.length_phrase = P2.length_phrase"                                   + "\n" +
            "AND    REPLACE(P1.lower_ns_phrase, '-', ' ') = P2.lower_ns_phrase;"           + "\n";
    stmt.execute(query); 

    query = "SELECT changefrom, changeto FROM tmp_normalised;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next())
    {
      String changefrom = rs.getString(1);
      String changeto   = rs.getString(2);

      query = "UPDATE term_phrase SET normalised = " + fixApostrophe(changeto)           + "\n" +
              "WHERE                  normalised = " + fixApostrophe(changefrom) + ";"   + "\n";
      Logger.debug(query);
      stmt1.execute(query);
    }
    rs.close();

    if(profiling > 0)
    {
      partEndTime = System.currentTimeMillis();
      partRuntimes[1] = (partEndTime - partStartTime) / 1000.0;
    }

    // **************************** PROCESS ACRONYMS ****************************

    if(profiling > 0)
    {
      partStartTime = System.currentTimeMillis();
    }

    acronyms = acronyms.toLowerCase();

    if (acronyms.equals("explicit"))
    {
      // --- assumption: acronyms are explicitly defined in text, e.g. 
      //     ... blah blah retinoic acid receptor (RAR) blah blah ...
      //                   ~~~~~~~~~~~~~~~~~~~~~~  ~~~

      // --- use this approach to extract acronyms:
      //     Schwartz A & Hearst M (2003) 
      //     A simple algorithm for identifying abbreviation definitions in biomedical text, 
      //     Pacific Symposium on Biocomputing 8:451-462 [http://biotext.berkeley.edu/software.html]

      // --- extract sentences that contain a pair of parentheses, e.g.
      //     ... blah blah ( blah blah ) blah blah ...
      //     NOTE: ( and ) have been replaced by -LRB- and -RRB- during POS tagging,
      //     so revert back to ( and ) to be able to run the Schwartz & Hearst algorithm

      query = "SELECT REPLACE(REPLACE(sentence, '-LRB- ', '('), ' -RRB-', ')')"    + "\n" +
              "FROM   data_sentence"                                               + "\n" +
              "WHERE  sentence LIKE '%-LRB- % -RRB-%';"                            + "\n";
      Logger.debug(query);
      rs = stmt.executeQuery(query);

      while (rs.next())
      {
        String sentence = rs.getString(1);

        // ---  run the Schwartz & Hearst algorithm on the given sentence;
        //      the results will be written into the tmp_acronym table
        extractPairs(sentence);
      }
      rs.close();

      // --- keep only those acronyms that match existing term candidates
      //     and link their normalised forms with the acronyms
      query = "INSERT INTO term_acronym(acronym, definition)"             + "\n" +
              "SELECT DISTINCT acronym, normalised"                       + "\n" +
              "FROM   tmp_acronym A, term_phrase P"                       + "\n" +
              "WHERE  LOWER(A.definition) = LOWER(P.phrase);"             + "\n";
      Logger.debug(query);
      stmt.execute(query);

      // --- delete multi-token acronyms (e.g. TNF alpha) and common abbreviations e.g. and i.e.
      query = "DELETE FROM term_acronym"                                  + "\n" +
              "WHERE  acronym LIKE '% %'"                                 + "\n" +
              "OR     acronym LIKE 'e.g.'"                                + "\n" +
              "OR     acronym LIKE 'i.e.';"                               + "\n";
      Logger.debug(query);
      stmt.execute(query);

      // --- delete what looks like a regular word mistaken for an acronym
      query = "DELETE FROM term_acronym"                                  + "\n" +
              "WHERE  LENGTH(acronym) > 6"                                + "\n" +
              "AND    acronym NOT LIKE '%-%'"                             + "\n" +
              "AND    LOWER(SUBSTR(acronym, 2)) = SUBSTR(acronym, 2);"    + "\n";
      Logger.debug(query);
      stmt.execute(query);

    }
    else if (acronyms.equals("implicit"))
    {
      // --- assumptions: 
      //     (1) acronyms are frequently used
      //     (2) expanded form also used in the corpus, but 
      //         these two are probably not linked explicitly
      //     e.g. blah ACL blah blah ACL blah blah anterior cruciate ligament blah blah 
      //               ~~~           ~~~           ~~~~~~~~~~~~~~~~~~~~~~~~~~

      // --- find tokens that are potential acronyms:
      //     (1) must contain an UPPERCASE letter, but no lowercase letters
      //     (2) must not start with - (avoids e.g. -LRB-)
      //     (3) must not end with . (avoids MR. so and so)
      //     (4) has to be at least 3 characters long as shorter ones are 
      //         likely to introduce false positive expanded forms as they 
      //         are more likely to match a random phrase as an expanded form 
      //         candidate
      //     (5) acronyms are frequently used, so a threshold is set to >MIN times
      query = "SELECT lemma, COUNT(*)"                                    + "\n" +
              "FROM   data_token"                                         + "\n" +
              "WHERE  UPPER(lemma) = lemma"                               + "\n" +
              "AND    2 < LENGTH(lemma) AND LENGTH(lemma) < 7"            + "\n" +
              "AND    lemma GLOB '*[A-Z]*'"                               + "\n" +
              "AND    (NOT lemma GLOB '-*')"                              + "\n" +
              "AND    (NOT lemma GLOB '*.')"                              + "\n" +
              "GROUP BY lemma"                                            + "\n" +
              "HAVING COUNT(*) > " + MIN + ";"                            + "\n";
      Logger.debug(query);
      rs = stmt.executeQuery(query);
      while (rs.next())
      {
        String acronym = rs.getString(1);

        // --- create a pattern from an acronym to match potential expanded forms
        //     e.g. ACL -> A% C% L% -> anterior cruciate ligament
        //     NOTE: the pattern is very strict in terms of white spaces to 
        //           minimise false positives as potential expanded forms
        int    length  = acronym.length();
        String pattern = "";
        for (int i = 0; i < length; i++) pattern += acronym.charAt(i) + "% ";
        pattern = pattern.trim();

        // --- extract potential expanded forms
        query = "INSERT INTO term_acronym(acronym, definition)"                                + "\n" +
                "SELECT DISTINCT " + fixApostrophe(acronym) + ", normalised"                   + "\n" +
                "FROM   term_phrase"                                                           + "\n" +
                "WHERE  phrase LIKE " + fixApostrophe(pattern)                                 + "\n" +
                "AND    LENGTH(phrase) - LENGTH(REPLACE(phrase, ' ', '')) < " + length + ";"   + "\n";
        Logger.debug(query);
        stmt1.execute(query);
      }
      rs.close();
    }
    else if (acronyms.equals("none")) {} // --- do nothing
    else Logger.debug("WARNING: Invalid acronym option. No acronym extraction will be performed.");


    // --- normalise acronyms as term candidates or part of other term candidates
    if (acronyms.equals("explicit") || acronyms.equals("implicit"))
    {

      // --- we want to have 1-to-1 mapping, so are looking to remove 
      //     less likely expanded forms

      // --- first remove less frequent candidates
      query = "DELETE FROM tmp_acronym;"; stmt.execute(query);

      query = "INSERT INTO tmp_acronym"                                                        + "\n" +
              "SELECT A1.acronym, A1.definition"                                               + "\n" +
              "FROM   term_acronym A1, term_acronym A2"                                        + "\n" +
              "WHERE  A1.acronym = A2.acronym"                                                 + "\n" +
              "AND    (SELECT COUNT(*) FROM term_phrase WHERE normalised = A1.definition) <"   + "\n" +
              "       (SELECT COUNT(*) FROM term_phrase WHERE normalised = A2.definition);"    + "\n";
      Logger.debug(query);
      stmt.execute(query);

      query = "DELETE FROM term_acronym"                                  + "\n" +
              "WHERE  acronym || definition IN"                           + "\n" +
              "(SELECT acronym || definition FROM tmp_acronym);"          + "\n";
      Logger.debug(query);
      stmt.execute(query);

      // --- then remove shorter candidates
      query = "DELETE FROM tmp_acronym;"; stmt.execute(query);

      query = "INSERT INTO tmp_acronym"                                   + "\n" +
              "SELECT A1.acronym, A1.definition"                          + "\n" +
              "FROM   term_acronym A1, term_acronym A2"                   + "\n" +
              "WHERE  A1.acronym = A2.acronym"                            + "\n" +
              "AND    length(A1.definition) < length(A2.definition);"     + "\n";
      Logger.debug(query);
      stmt.execute(query);

      query = "DELETE FROM term_acronym"                                  + "\n" +
              "WHERE  acronym || definition IN"                           + "\n" +
              "(SELECT acronym || definition FROM tmp_acronym);"          + "\n";
      Logger.debug(query);
      stmt.execute(query);

      // --- if there are still ambiguous acronyms, then use brute force:
      //     simply choose the first one in alphabetic order
      query = "DELETE FROM tmp_acronym;"; stmt.execute(query);

      query = "INSERT INTO tmp_acronym"                                   + "\n" +
              "SELECT A1.acronym, A1.definition"                          + "\n" +
              "FROM   term_acronym A1, term_acronym A2"                   + "\n" +
              "WHERE  A1.acronym = A2.acronym"                            + "\n" +
              "AND    A1.definition > A2.definition;"                     + "\n";
      Logger.debug(query);
      stmt.execute(query);

      query = "DELETE FROM term_acronym"                                  + "\n" +
              "WHERE  acronym || definition IN"                           + "\n" +
              "(SELECT acronym || definition FROM tmp_acronym);"          + "\n";
      Logger.debug(query);
      stmt.execute(query);


      // --- treat acronyms that are NOT already NESTED within multi-word term candidates
      //     as stand-alone multi-word term candidates
      // --- insert mentions of such acronyms into the term_phrase table
      query = "INSERT INTO term_phrase(id, sentence_id, token_start, token_length, phrase, normalised)"            + "\n" +
              "SELECT sentence_id || '.' || position, sentence_id, position, 1, acronym, definition"               + "\n" +
              "FROM   data_token T, term_acronym A"                                                                + "\n" +
              "WHERE  T.token = A.acronym"                                                                         + "\n" +
              "EXCEPT"                                                                                             + "\n" +
              "SELECT T.sentence_id || '.' || T.position, T.sentence_id, T.position, 1, acronym, definition"       + "\n" +
              "FROM   data_token T, term_acronym A, term_phrase P"                                                 + "\n" +
              "WHERE  T.token = A.acronym"                                                                         + "\n" +
              "AND    T.sentence_id = P.sentence_id"                                                               + "\n" +
              "AND    P.token_start <= T.position"                                                                 + "\n" +
              "AND    T.position < P.token_start + P.token_length;"                                                + "\n";
      Logger.debug(query);
      stmt.execute(query);

      // --- now replace NESTED mentions of acronyms with their EXPANDED FROMS
      query = "DELETE FROM tmp_normalised;"; stmt.execute(query);

      query = "INSERT INTO tmp_normalised(changefrom, changeto)"                                                                                + "\n" +
              "SELECT DISTINCT P.normalised, REPLACE(' ' || P.normalised || ' ', ' ' || LOWER(A.acronym) || ' ', ' ' || A.definition || ' ')"   + "\n" +
              "FROM   term_phrase P, term_acronym A"                                                                                            + "\n" +
              "WHERE  ' ' || P.normalised || ' ' LIKE '% ' || LOWER(A.acronym) || ' %';"                                                        + "\n";
      Logger.debug(query);
      stmt.execute(query);

      query = "SELECT changefrom, changeto FROM tmp_normalised;";
      Logger.debug(query);
      rs = stmt.executeQuery(query);

      while (rs.next())
      {
        String changefrom = rs.getString(1);
        String changeto   = rs.getString(2);

        changeto = sortString(changeto);

        query = "UPDATE term_phrase SET normalised = " + fixApostrophe(changeto)           + "\n" +
                "WHERE                  normalised = " + fixApostrophe(changefrom) + ";"   + "\n";
        Logger.debug(query);
        stmt1.execute(query);
      }
      rs.close();


      // --- re-normalise acronyms that have different TOKENISATION,
      //     e.g. NF-kappa B vs. NF-kappaB
      // NOTE: (1) acronyms were not present as term candidates (phrases!) 
      //           when we did the same thing before
      //       (2) 2-token variants (e.g. NF-kappa B) may have been picked 
      //           up as term candidates (phrases!) independently, but we 
      //           would still want to link them to other variants
      query = "DELETE FROM tmp_normalised;"; stmt.execute(query);

      query = "INSERT INTO tmp_normalised(changefrom, changeto)"                                  + "\n" +
              "SELECT DISTINCT P1.normalised, P2.normalised"                                      + "\n" +
              "FROM   term_phrase P1, term_phrase P2"                                             + "\n" +
              "WHERE  P1.phrase LIKE '% %'"                                                       + "\n" +
              "AND    P2.phrase IN (SELECT acronym FROM term_acronym)"                            + "\n" +
              "AND    LOWER(SUBSTR(P1.phrase, 1, 1)) = LOWER(SUBSTR(P2.phrase, 1, 1))"            + "\n" +
              "AND    LOWER(REPLACE(P1.phrase, ' ', '')) = LOWER(P2.phrase);"                     + "\n";
      Logger.debug(query);
      stmt.execute(query);

      query = "SELECT changefrom, changeto FROM tmp_normalised;";
      Logger.debug(query);
      rs = stmt.executeQuery(query);

      while (rs.next())
      {
        String changefrom = rs.getString(1);
        String changeto   = rs.getString(2);

        query = "UPDATE term_phrase SET normalised = " + fixApostrophe(changeto)           + "\n" +
                "WHERE                  normalised = " + fixApostrophe(changefrom) + ";"   + "\n";
        Logger.debug(query);
        stmt1.execute(query);
      }
      rs.close();
    }

    if(profiling > 0)
    {
      partEndTime = System.currentTimeMillis();
      partRuntimes[2] = (partEndTime - partStartTime) / 1000.0;
    }

    // ********************** ENF OF PROCESSING ACRONYMS ***********************


    // ******************* SELECT NORMALISED TERM CANDIDATES *******************

    if(profiling > 0)
    {
      partStartTime = System.currentTimeMillis();
    }

    query = "INSERT INTO term_normalised(normalised)"                   + "\n" +
            "SELECT DISTINCT normalised"                                + "\n" +
            "FROM   term_phrase"                                        + "\n" +
            "WHERE  LENGTH(normalised) > 5"                             + "\n" +
            "AND    normalised LIKE '% %';";
    Logger.debug(query);
    stmt.execute(query);


    // ******************* TOKENISE NORMALISED TERM CANDIDATES ******************
    query = "SELECT rowid, normalised FROM term_normalised;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next())
    {
      int id = rs.getInt(1);
      String normalised = rs.getString(2) + " ";

      int len = 0;
      int t = -1;
      while ((t = normalised.indexOf(" ")) > 0)
      {
        String token = normalised.substring(0, t);
        normalised = normalised.substring(t+1);

        token = token.replaceAll("\\.", "");   // --- e.g. U.K., Dr., St. -> UK, Dr, St

        query = "INSERT INTO term_bag(id, token)\n" +
                "VALUES(" + id + ", " + fixApostrophe(token) + ");";
        Logger.debug(query);
        stmt1.execute(query);

        len++;
      }

      query = "UPDATE term_normalised SET len = " + len + " WHERE rowid = " + id + ";";
      stmt1.execute(query);
    }
    rs.close();


    // ******************* SELECT DISTINCT TOKENS *******************
    query = "INSERT INTO token(token)\n" +
            "SELECT DISTINCT token FROM term_bag;";
    Logger.debug(query);
    stmt.execute(query);


    // ******************* CALCULATE TOKEN SIMILARITY *******************

    // --- select all tokens for a dictionary
    String lexicon = "";
    query = "SELECT token"                          + "\n" +
            "FROM   token"                          + "\n" +
            "WHERE  LENGTH(token) > 2 * " + max     + "\n" +
            "ORDER BY token ASC;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next()) lexicon += rs.getString(1) + "\n";
    rs.close();

    String dictionaryFile = "../resources/dictionary.txt";

    // --- process each token with Jazzy
    rs = stmt.executeQuery(query);
    while (rs.next())
    {
      String token = rs.getString(1);

      // --- remove 1st token from lexicon
      // NB: similarity matrix is symmetric, so no need to add
      //     the token back once it's compared to all others
      lexicon = lexicon.substring(lexicon.indexOf("\n") + 1);

      // --- write lexicon into dictionary file
      writeToFile(lexicon, "../resources/dictionary.txt");

      // --- prepare spell checker
      SpellDictionary dictionary   = new SpellDictionaryHashMap(new File(dictionaryFile));
      SpellChecker    spellChecker = new SpellChecker(dictionary);

      // --- get all spelling suggestions with max operations away
      List suggestions = spellChecker.getSuggestions(token, max);
      if (!suggestions.isEmpty())
      {
        for (Iterator i = suggestions.iterator(); i.hasNext();)
        {
          String token2 = "" + i.next();  // --- NOTE: token < token2

          query = "INSERT INTO token_similarity(token1, token2)\n" +
                  "VALUES (" + fixApostrophe(token) + "," + fixApostrophe(token2) + ");";
          Logger.debug(query);
          stmt1.execute(query);

          query = "INSERT INTO token_similarity(token1, token2)\n" +
                  "VALUES (" + fixApostrophe(token2) + "," + fixApostrophe(token) + ");";
          Logger.debug(query);
          stmt1.execute(query);
        }
      }
    }
    rs.close();

    // --- create transitive closure
    Boolean closed = false;
    while (!closed)
    {
      closed = true;

      // --- if x ~ y, y ~ z & x != z & x !~ z, then add x ~ z
      query = "SELECT DISTINCT S1.token1, S2.token2"                + "\n" +
              "FROM   token_similarity S1, token_similarity S2"     + "\n" +
              "WHERE  S1.token2 =  S2.token1"                       + "\n" +
              "AND    S1.token1 <> S2.token2"                       + "\n" +
              "EXCEPT"                                              + "\n" +
              "SELECT token1, token2"                               + "\n" +
              "FROM   token_similarity;";
      Logger.debug(query);
      rs = stmt.executeQuery(query);
      while (rs.next()) 
      {
        String token1 = rs.getString(1);
        String token2 = rs.getString(2);

        query = "INSERT INTO token_similarity(token1, token2)"      + "\n" +
                "VALUES (" + fixApostrophe(token1) + ", " + fixApostrophe(token2) + ");";
        Logger.debug(query);
        stmt1.execute(query);

        closed = false;
      }
      rs.close();
    }

    if(profiling > 0)
    {
      partEndTime = System.currentTimeMillis();
      partRuntimes[3] = (partEndTime - partStartTime) / 1000.0;
    }

    // ******************* EXPAND TERMS WITH SIMILAR TOKENS *******************

    if(profiling > 0)
    {
      partStartTime = System.currentTimeMillis();
    }

    query = "INSERT INTO term_bag(id, token)"      + "\n" +
            "SELECT DISTINCT id, token2"           + "\n" +
            "FROM term_bag, token_similarity"      + "\n" +
            "WHERE token = token1;";
    Logger.debug(query);
    stmt.execute(query);

    int total = 0;
    query = "SELECT MAX(rowid) FROM term_normalised;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    if (rs.next()) total = rs.getInt(1);
    rs.close();

    for (int id = 1; id <= total; id++)
    {
      String expanded = "";

      query = "SELECT token"                       + "\n" +
              "FROM   term_bag"                    + "\n" +
              "WHERE  id = " + id                  + "\n" +
              "ORDER BY token;";
      Logger.debug(query);
      rs = stmt.executeQuery(query);
      while (rs.next())
      {
        expanded += " " + rs.getString(1);
      }
      rs.close();

      expanded = expanded.substring(1);

      query = "UPDATE term_normalised SET expanded = " + fixApostrophe(expanded) + " WHERE rowid = " + id + ";";
      Logger.debug(query);
      stmt.execute(query);
    }


    // ******************* IDENTIFY TERM NESTEDNESS *******************
    for (int id = 1; id <= total; id++)
    {
      query = "SELECT token FROM term_bag WHERE id = " + id + ";";
      Logger.debug(query);
      rs = stmt.executeQuery(query);
      query = "";
      while (rs.next()) 
      {
        String token = rs.getString(1);

        query += "\nINTERSECT\n" + 
                 "SELECT DISTINCT id FROM term_bag WHERE token = " + fixApostrophe(token) + " AND id <> " + id;
      }
      rs.close();

      if (query.length() > 0)
      {
        query = query.substring(11) + ";";
        Logger.debug(query);
        rs = stmt.executeQuery(query);
        while (rs.next()) 
        {
          int parent = rs.getInt(1);
          query = "INSERT INTO term_nested_aux(parent, child)\n" +
                  "VALUES (" + parent + ", " + id + ");";
          Logger.debug(query);
          stmt1.execute(query);
        }
      }
      rs.close();
    }

    query = "INSERT INTO term_nested(parent, child)"                               + "\n" +
            "SELECT DISTINCT N1.expanded, N2.expanded"                             + "\n" +
            "FROM   term_normalised N1, term_normalised N2, term_nested_aux A"     + "\n" +
            "WHERE  N1.rowid = A.parent"                                           + "\n" +
            "AND    N2.rowid = A.child"                                            + "\n" +
            "AND    N1.expanded <> N2.expanded;";
    Logger.debug(query);
    stmt.execute(query);

    if(profiling > 0)
    {
      partEndTime = System.currentTimeMillis();
      partRuntimes[4] = (partEndTime - partStartTime) / 1000.0;
    }

    // ******************* CALCULATE TERMHOOD *******************

    if(profiling > 0)
    {
      partStartTime = System.currentTimeMillis();
    }

    query = "INSERT INTO term_termhood(expanded, len, s, nf)\n" +
            "SELECT DISTINCT expanded, len, 0, 0 FROM term_normalised;";
    Logger.debug(query);
    stmt.execute(query);

    // --- calculate frequency of exact occurrence
    query = "SELECT N.expanded, COUNT(*)"                       + "\n" +
            "FROM   term_normalised N, term_phrase P"           + "\n" +
            "WHERE  N.normalised = P.normalised"                + "\n" +
            "GROUP BY N.expanded;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next()) 
    {
      String expanded = rs.getString(1);
      int    f = rs.getInt(2);

      query = "UPDATE term_termhood SET f = " + f + " WHERE expanded = " + fixApostrophe(expanded) + ";";
      Logger.debug(query);
      stmt1.execute(query);
    }
    rs.close();

    // --- calculate the number of parent term candidates
    query = "SELECT child, COUNT(*)"     + "\n" +
            "FROM   term_nested"         + "\n" +
            "GROUP BY child;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next())
    {
      String child = rs.getString(1);
      int    s = rs.getInt(2);

      query = "UPDATE term_termhood SET s = " + s + " WHERE expanded = " + fixApostrophe(child) + ";";
      Logger.debug(query);
      stmt1.execute(query);
    }
    rs.close();

    // --- calculate the frequency of nested occurrence
    query = "SELECT child, COUNT(*)"                                     + "\n" +
            "FROM   term_nested N, term_normalised C, term_phrase P"     + "\n" +
            "WHERE  N.parent = C.expanded"                               + "\n" +
            "AND    C.normalised = P.normalised"                         + "\n" +
            "GROUP BY child;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next()) 
    {
      String child = rs.getString(1);
      int    nf = rs.getInt(2);

      query = "UPDATE term_termhood SET nf = " + nf + " WHERE expanded = " + fixApostrophe(child) + ";";
      Logger.debug(query);
      stmt1.execute(query);
    }
    rs.close();

    // --- add up frequencies: f(t)
    query = "UPDATE term_termhood SET f = f + nf;";
    Logger.debug(query);
    stmt1.execute(query);

    // --- calculate C-value
    query = "SELECT expanded, len, f, s, nf FROM term_termhood;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next()) 
    {
      String expanded = rs.getString(1);
      int len = rs.getInt(2);
      int f   = rs.getInt(3);
      int s   = rs.getInt(4);
      int nf  = rs.getInt(5);

      double c = cValue(len, f, s, nf);

      query = "UPDATE term_termhood SET c = " + c + " WHERE expanded = " + fixApostrophe(expanded) + ";";
      Logger.debug(query);
      stmt1.execute(query);
    }
    rs.close();

    if(profiling > 0)
    {
      partEndTime = System.currentTimeMillis();
      partRuntimes[5] = (partEndTime - partStartTime) / 1000.0;

      // --- stop timing term recogntion
      endTime = System.currentTimeMillis();
      runTime = ((endTime - startTime) / 1000.0) ;// 60; // --- run time in minutes
      Logger.debug("Term recognition done in " + runTime + " seconds.\n");
    }

    // --- write results to output files

    export(); 

    long anotStartTime = 0;
    long anotEndTime;
    double anotRuntime;
    if(profiling > 0)
    {
      anotStartTime = System.currentTimeMillis();
    }

    annotate();

    if(profiling > 0)
    {
      anotEndTime = System.currentTimeMillis();
      anotRuntime = (anotEndTime - anotStartTime) / 1000.0;

      System.err.println("\nProfiling numbers (s):");
      System.err.println("LoadDocuments: "+loadDocsRuntime+", Term recognition: " + runTime + ", Annotation: "+anotRuntime+"");
      System.err.println("Parts of Term Recognition: "+partRuntimes[0]+", "+partRuntimes[1]+", "+partRuntimes[2]+", "+partRuntimes[3]+", "+partRuntimes[4]+", "+partRuntimes[5]);
    }

    con.commit();

    con.createStatement().executeUpdate("backup to ../out/output.db");

    stmt1.close();

    close();  // --- SQLite database connection
  }
  catch(Exception e){e.printStackTrace();}
}

// ********************** END OF MAIN ***********************


// ----------------------------------------------------------
// --- 
// ----------------------------------------------------------
public static double cValue(int len, int f, double s, double nf)
{
  double c = f;

  if (s > 0) c -= nf / s;

  c *= Math.log(len);

  return c;
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- generalise tags
// ----------------------------------------------------------
public static String general(String tag)
{
  String gtag = tag;

       if (tag.length() <= 1)               gtag = "PUN";
  else if (tag.equals("PRP$"))              gtag = "PRP";
  else if (tag.equals("WP$"))               gtag = "WP";
  else if (tag.substring(0,2).equals("JJ")) gtag = "JJ";
  else if (tag.substring(0,2).equals("NN")) gtag = "NN";
  else if (tag.substring(0,2).equals("RB")) gtag = "RB";
  else if (tag.substring(0,2).equals("VB")) gtag = "VB";

  return gtag;
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- open database connection
// ----------------------------------------------------------
public static void open()
{
  Logger.debug("\nDatabase.open(): open database connection\n");

  try
  {
    // --- load the postgresql jdbc driver
    Class.forName(driver);

    con = DriverManager.getConnection(url);
    con.setAutoCommit(false);
  }
  catch (SQLException ex)        {explain(ex);}
  catch (java.lang.Exception ex) {ex.printStackTrace();}
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- close database connection
// ----------------------------------------------------------
public static void close()
{
  Logger.debug("\nDatabase.close(): close database connection");

  try {con.close();}
  catch (SQLException ex) {explain(ex);}
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- explain the SQL exception cought
// ----------------------------------------------------------
public static void explain(SQLException ex)
{
  System.out.println ("\n*** SQLException caught ***\n");

  while (ex != null) 
  {
    Logger.debug("SQLState: " + ex.getSQLState());
    Logger.debug("Message:  " + ex.getMessage());
    Logger.debug("Vendor:   " + ex.getErrorCode());
    Logger.debug("");

    ex = ex.getNextException();
  }
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- fix apostrophe, a special character in SQL, 
// --- so it can be imported (' --> '')
// ----------------------------------------------------------
private static String fixApostrophe(String inputString)
{
  if (inputString == null) return "NULL";

  String outputString = "";
  int i, l;

  l = inputString.length();

  for (i = 0; i < l; i++)
  {
    char c = inputString.charAt(i);
    outputString += c;
    if (c == '\'') outputString += "'";
  }

  return "'" + outputString + "'";
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- total # of whitespaces in a string
// ----------------------------------------------------------
private static int whitespaces(String inputString)
{
  if (inputString == null) return 0;
  else return inputString.length() - inputString.replaceAll(" ", "").length();
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- load setting from file (settings.txt), e.g.
// ----------------------------------------------------------
// txt plain
// pattern (((((NN|JJ) )*NN) IN (((NN|JJ) )*NN))|(((NN|JJ) )+NN))
// stoplist stoplist.txt
// max 3
// min 2
// ----------------------------------------------------------
private static void loadSettings(String file)
{
  Logger.debug("Loading settings from " + file + "...");
  File settings = new File(file);

  if (!settings.exists() || !settings.isFile())
  {
    Logger.debug("Settings file " + file + " does not exist.");
    Logger.debug("Using defaults values:");
    Logger.debug("* pattern  : " + pattern);
    Logger.debug("* stoplist : " + stoplist);
    Logger.debug("* max      : " + max);
    Logger.debug("* min      : " + min);
  }
  else
  {
    try
    {
      // --- read settings from file
      BufferedReader in = new BufferedReader(new FileReader(settings));
      String line;

      while((line = in.readLine())!= null)
      {
        int i = line.indexOf(' ');
        if (i > 0)
        {
          String parameter = line.substring(0, i);
          String value = line.substring(i+1);

          // --- validate & initialise parameters
          if (parameter.equals("pattern"))
          {
            try
            {
              Pattern r = Pattern.compile(value);
              pattern = value;
            }
            catch(Exception e)
            {
              Logger.debug("Invalid POS pattern: " + value);
              Logger.debug("Using default: pattern = " + pattern);
            }
          }
          else if (parameter.equals("stoplist"))
          {
            File sl = new File(stoplist);

            if (!sl.exists() || !sl.isFile()) 
            {
              Logger.debug("Stoplist file " + file + " does not exist.");
              Logger.debug("Using default: stoplist = " + stoplist);
            }
            else stoplist = value;
          }
          else if (parameter.equals("max"))
          {
            try
            {
              int number = Integer.parseInt(value);

              if (0 < number && number < 10) max = number;
              else
              {
                Logger.debug("Invalid max value: " + value);
                Logger.debug("Using default: max = " + max);
              }
            }
            catch(Exception e)
            {
              Logger.debug("Invalid max value: " + value);
              Logger.debug("Using default: max = " + max);
            }
          }
          else if (parameter.equals("min"))
          {
            try
            {
              int number = Integer.parseInt(value);

              min = number;
            }
            catch(Exception e)
            {
              Logger.debug("Invalid min value: " + value);
              Logger.debug("Using default: min = " + min);
            }
          }
          else if (parameter.equals("MIN"))
          {
            try
            {
              int number = Integer.parseInt(value);

              MIN = number;
            }
            catch(Exception e)
            {
              Logger.debug("Invalid MIN value: " + value);
              Logger.debug("Using default: MIN = " + MIN);
            }
          }
          else if (parameter.equals("acronyms"))
          {
            try
            {
              acronyms = value;
            }
            catch(Exception e)
            {
              Logger.debug("Invalid acronyms value: " + value);
              Logger.debug("Using default: acronyms = " + acronyms);
            }
          }
	  else if(parameter.equals("profiling"))
	  {
            try
            {
              profiling = Integer.parseInt(value);
            }
            catch(Exception e)
            {
              Logger.debug("Invalid profiling value: " + value);
              Logger.debug("Using default: profiling = " + profiling);
            }
 	  }
          else Logger.debug("Invalid line: " + line);
        }
        else Logger.debug("Invalid line: " + line);
      }
    }
    catch(Exception e){e.printStackTrace();}
  }
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- load stoplist from file into database
// ----------------------------------------------------------
private static void loadStoplist(String file)
{
  try
  {
    Logger.debug("Loading stoplist from " + file + "...");

    Statement stmt = con.createStatement();

    File in = new File(file);

    if (!in.exists() || !in.isFile()) Logger.debug("Invalid stoplist file: " + file);
    else 
    {
      // --- read stopwords from file
      BufferedReader fin = new BufferedReader(new FileReader (in));
      String word;

      while ((word = fin.readLine()) != null)
      {
        String query = "INSERT INTO stopword(word) VALUES(" + fixApostrophe(word) + ");";
        stmt.execute(query);
      }
    }

    stmt.close();
  }
  catch(Exception e){e.printStackTrace();}
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- write string to file
// ----------------------------------------------------------
private static void writeToFile(String str, String out) throws Exception
{
  FileOutputStream fop = null;
  File file = new File(out);
  fop = new FileOutputStream(file);
  if (!file.exists()) file.createNewFile();
  byte[] contentInBytes = str.getBytes();
  fop.write(contentInBytes);
  fop.flush();
  fop.close();
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- load & preprocess documents
// ----------------------------------------------------------
private static void loadDocuments(String folder) throws Exception
{
  Logger.debug("Loading & pre-processing documents from folder " + folder + "...");

  long startTime, endTime, runTime;
  startTime = System.currentTimeMillis();
  Logger.debug("Start time: " + ((startTime % 86400000) / 3600000 + 1) + ":" + (startTime % 3600000) / 60000 + ":" + (startTime % 60000) / 1000);

  Statement stmt  = con.createStatement();
  String query;
  Boolean empty = true;

  File in = new File(folder);
  if (in.isDirectory())
  {
    // --- POS tag input text

    File[] file = in.listFiles();
    for (int i = 0; i < file.length; i++)
    {
      empty = false;

      Logger.debug("Processing " + file[i].getPath() + "...");

      String doc_id = file[i].getName();
      int extension = doc_id.lastIndexOf('.');
      if (extension > 0) doc_id = doc_id.substring(0, extension);

      // --- read document
      BufferedReader fin = new BufferedReader(new FileReader (file[i]));
      String line;
      String content = "";
      while ((line = fin.readLine()) != null) content += line + "\n";

      String verbatim = content;   // --- save the original (raw) content

      // --- clean text content before POS tagging
      content = pretagging(content);

      // --- add document to the database
      query = "INSERT INTO data_document(id, document, verbatim)\n" +
              "VALUES('" + doc_id + "', " + fixApostrophe(content) + ", " + fixApostrophe(verbatim) + ");";
      Logger.debug(query);
      stmt.execute(query);

      // --- split sentences & tokenise them
      List<ArrayList<? extends HasWord>> sentences = tagger.tokenizeText(new BufferedReader(new StringReader(content)));

      int s = 0; // --- sentence number: 1, ... , n

      // --- for each sentence
      for (List<? extends HasWord> sentence : sentences)
      {
        s++;
        String sentence_id = doc_id + "." + s;

        // --- tag sentence
        ArrayList<TaggedWord> taggedSentence = tagger.tagSentence(sentence);

        String sentenceString = Sentence.listToString(sentence, false);
        String taggedSentenceString = Sentence.listToString(taggedSentence, false);

        Logger.debug("Sentence (plain): " + sentenceString);
        Logger.debug("Sentence (tagged): " + taggedSentenceString);

        // --- make sure determiner "The" is tagged correctly
        taggedSentenceString = taggedSentenceString.replaceAll("The/NNP", "The/DET");

        // --- make sure special characters are tagged correctly
        taggedSentenceString = taggedSentenceString.replaceAll("&gt;/[A-Z]+", "&gt;/SYM");
        taggedSentenceString = taggedSentenceString.replaceAll("&lt;/[A-Z]+", "&lt;/SYM");
        taggedSentenceString = taggedSentenceString.replaceAll("%/[A-Z]+", "%/SYM");
        taggedSentenceString = taggedSentenceString.replaceAll("&/[A-Z]+", "&/SYM");
        taggedSentenceString = taggedSentenceString.replaceAll("~/[A-Z]+", "~/SYM");
        taggedSentenceString = taggedSentenceString.replaceAll("\\^/[A-Z]+", "^/SYM");
        taggedSentenceString = taggedSentenceString.replaceAll("\\+/[A-Z]+", "+/SYM");
        taggedSentenceString = taggedSentenceString.replaceAll("\\*/[A-Z]+", "*/SYM");
        taggedSentenceString = taggedSentenceString.replaceAll("(\\/)/[A-Z]+", "$1/SYM");

        // --- re-tag slashed words as unknown (these are usually not tokenised/tagged properly)
        taggedSentenceString = taggedSentenceString.replaceAll("(\\/[A-Za-z0-9\\-]+)/[A-Z]+", "$1/XX");

        // --- make sure numbers are tagged as such
        taggedSentenceString = taggedSentenceString.replaceAll("( [0-9]+)/[A-Z]+", "$1/CD");
  
        // --- add sentence to the database
        query = "INSERT INTO data_sentence(id, doc_id, position, sentence, tagged_sentence)\n" +
                "VALUES('" + sentence_id + "', '" + doc_id + "'," + s + ", " + fixApostrophe(sentenceString) + "," + fixApostrophe(taggedSentenceString) + ");";
        Logger.debug(query);
        stmt.execute(query);

        String tags = "";

        // --- separate tagged tokens, e.g.
        //     I/PRP had/VBD a/DT tele/JJ phone/NN call/NN ...
        String[] parts = taggedSentenceString.split(" ");
        for (int j = 0; j < parts.length; j++)
        {
          // --- separate token from its tag, e.g.
          //     token = "had", tag = "VBD"
          String part = parts[j];
          if (part.indexOf('/') < 0)
          {
            // --- patch tag if tagger failed
            part += "/XX";
            Logger.debug("WARNING: Sentence above not tagged properly!");
          }

          int p = part.lastIndexOf("/");
          String token = part.substring(0, p).replaceAll("\\\\/", "/");
          String tag   = part.substring(p+1);

          // --- generalise tag, e.g. VBD -> VB
          String gtag = general(tag);

          // --- lemmatise token, e.g. had ->  have
          POS pos = null;
          if (tag.length() > 1) pos = wordnet.getPOS(tag);
          String lemma = token;
          if(pos != null) lemma = wordnet.lemmatize(token, pos);

          // --- stem token, e.g. Friday -> fridai
          String stem = porter.Stem(token.toLowerCase());

          Logger.debug(token + "\t" + tag + "\t" + gtag + "\t" + stem + "\t" + lemma);

          // --- add token to the database
          query = "INSERT INTO data_token(sentence_id, position, token, lower_token, stem, lemma, tag, gtag)\n" +
                  "VALUES('" + sentence_id + "', " + (j+1) + ", " + fixApostrophe(token) + ", " + fixApostrophe(token).toLowerCase() + ", " + fixApostrophe(stem) + ", " + fixApostrophe(lemma) +
                  ", '" + tag + "', '" + gtag + "');";
          Logger.debug(query);
          stmt.execute(query);

          tags += gtag + " ";
        }

        query = "UPDATE data_sentence SET tags = '" + tags + "' WHERE id = '" + sentence_id + "';";
        Logger.debug(query);
        stmt.execute(query);
      }
    }
  }

  query = "UPDATE data_token SET wntag = 'a' WHERE gtag = 'JJ';"; stmt.execute(query);
  query = "UPDATE data_token SET wntag = 'r' WHERE gtag = 'RB';"; stmt.execute(query);
  query = "UPDATE data_token SET wntag = 'n' WHERE gtag = 'NN';"; stmt.execute(query);
  query = "UPDATE data_token SET wntag = 'v' WHERE gtag = 'VB';"; stmt.execute(query);

  endTime = System.currentTimeMillis();
  runTime = ((endTime - startTime) / 1000) ;// 60; // --- run time in minutes
  Logger.debug("Document pre-processing done in " + runTime + " min.\n");

  stmt.close();

  if (empty)
  {
    Logger.debug("WARNING: Input folder empty!");
    System.exit(0);
  }
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- make sure tokens like "5years" are split in order to 
//     improve subsequent POS tagging and allow stop words 
//     like "year" to be matched
// ----------------------------------------------------------
private static String pretagging(String txt)
{
  String[] unit = {"meter",
                   "metre",
                   "mile",
                   "centi",
                   "milli",
                   "kilo",
                   "gram",
                   "sec",
                   "min",
                   "hour",
                   "hr",
                   "day",
                   "week",
                   "month",
                   "year",
                   "liter",
                   "litre"};

  String[] abbr = {"m",
                   "cm",
                   "mm",
                   "kg",
                   "g",
                   "mg",
                   "s",
                   "h",
                   "am",
                   "pm",
                   "l",
                   "ml"};
  int i;

  // --- insert white space in front of a unit where necessary
  for (i = 0; i < unit.length; i++)
  txt = txt.replaceAll("[0-9]" + unit[i], "0 " + unit[i]);

  for (i = 0; i < abbr.length; i++)
  txt = txt.replaceAll("[0-9]" + abbr[i] + " ", "0 " + abbr[i] + " ");

  // --- compress repetative punctuation into a single character
  txt = txt.replaceAll("\\!\\!+", "!");
  txt = txt.replaceAll("\\?\\?+", "?");
  txt = txt.replaceAll("\\.\\.+", ".");
  txt = txt.replaceAll("\\-\\-+", "-");
  txt = txt.replaceAll("__+", "_");
  txt = txt.replaceAll("~~+", "~");

  // --- remove long gene sequences
  txt = txt.replaceAll("[ACGT ]{6,}", "");

  // --- separate possible titles/sections written in uppercase
  txt = txt.replaceAll("([A-Z\\s]{7,})\\s", ". $1. ");

  // --- normalise non-ASCII characters
  txt = Normalizer.normalize(txt, Normalizer.Form.NFD);
  txt = txt.replaceAll("[^\\x00-\\x7F]", "");

  return txt;
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- export results
//  1. output.html  = ranked table with variants grouped together
//  2. output.txt   = dictionary, one entry per line
//  3. output.mixup = MinorThird program that annotates suggested 
//                    terms in text
// ----------------------------------------------------------
private static void export() throws Exception
{
  Logger.debug("Exporting results to output.*...");

  Statement stmt  = con.createStatement();
  Statement stmt1 = con.createStatement();
  Statement stmt2 = con.createStatement();
  ResultSet rs, rs1, rs2;
  String query;
  DecimalFormat f = new DecimalFormat("0.0000");

  int rank = 0;
  int id = 0;
  double score = -1;

  // --- initialise outputs
  StringBuffer output  = new StringBuffer(
                         "<html>"                               + "\n" +
                         "<head>"                               + "\n" +
                         "  <title>FlexiTerm list</title>"      + "\n" +
                         "</head>"                              + "\n" +
                         "<body>"                               + "\n" +
                         "<table border=\"1\">"                 + "\n" +
                         "  <tr>"                               + "\n" +
                         "    <th>ID</th>"                      + "\n" +
                         "    <th>Term variants</th>"           + "\n" +
                         "    <th>Score</th>"                   + "\n" +
                         "    <th>Rank</th>"                    + "\n" +
                         "  </tr>"                              + "\n");
  StringBuffer termlist = new StringBuffer("");
  StringBuffer mixup    = new StringBuffer("");
  StringBuffer csv      = new StringBuffer("Rank,Term representative,Score,Frequency");

  query = "SELECT expanded, c, f"                        + "\n" +
          "FROM   term_termhood"                         + "\n" +
          "WHERE  f > " + min                            + "\n" +
          "ORDER BY c DESC;";
  Logger.debug(query);
  rs = stmt.executeQuery(query);
  while (rs.next())
  {
    String expanded = rs.getString(1);
    double        c = rs.getDouble(2);
    int   frequency = rs.getInt(3);

    if (c != score) {rank++; score = c;}

    String variants = "";
    String representative = "";
    String mixupvar = "";

    // --- find all term variants
    query = "SELECT LOWER(P.phrase), COUNT(*)"                 + "\n" +
            "FROM   term_normalised N, term_phrase P"          + "\n" +
            "WHERE  N.expanded = " + fixApostrophe(expanded)   + "\n" +
            "AND    N.normalised = P.normalised"               + "\n" +
            "GROUP BY LOWER(P.phrase)"                         + "\n" +
            "ORDER BY COUNT(*) DESC, LOWER(P.phrase);"         + "\n";
    Logger.debug(query);
    rs1 = stmt1.executeQuery(query);
    while (rs1.next())
    {
      String variant = rs1.getString(1);

      if (! variant.contains(" ")) // --- is acronym?
      {
        // --- get the case-sensitive version
        query = "SELECT acronym"                                            + "\n" +
                "FROM   term_acronym"                                       + "\n" +
                "WHERE  LOWER(acronym) = " + fixApostrophe(variant) + ";"   + "\n";
        rs2 = stmt2.executeQuery(query);
        if (rs2.next()) variant = rs2.getString(1);
        rs2.close();
      }

      if (representative.equals(""))
      {
        id++;

        representative = variant;
        query = "UPDATE term_termhood SET representative = " + fixApostrophe(representative) + " WHERE expanded = " + fixApostrophe(expanded) + ";";
        Logger.debug(query);
        stmt2.execute(query);

        csv.append("\n" + rank + ",\"" + representative + "\"," + f.format(c) + "," + frequency);
      }

      query = "INSERT INTO output_table(id, rank, representative, variant, f, c)"                                                                             + "\n" +
              "VALUES (" + id + ", " + rank + ", " + fixApostrophe(representative) + ", " + fixApostrophe(variant) + ", " + frequency + ", " + score + ");"   + "\n";
      Logger.debug(query);
      stmt2.execute(query);

      termlist.append(variant + "\n");

      variants += "      " + variant + "<br/>\n";

      variant = mixupTokens(variant);

      String line = "";
      String[] tokens = variant.split(" ");
      String eq = "eqi";                 // --- case-insensitive token match by default
      if (tokens.length == 1) eq = "eq"; // --- case-sensitive token match for stand-alone acronyms
      for (int i = 0; i < tokens.length; i++)
      {
        String token = tokens[i];
        line += eq + "('" + token.replaceAll("'", "\\\\'") + "') ";
      }
      line = "||\n... [ " + line + "] ... ";
      mixupvar += line;
    }
    rs1.close();

    mixup.append("defSpanType term_" + id + " =: " + mixupvar.substring(2) + ";\n\n");

    output.append("  <tr>"                                                                             + "\n" +
                  "    <td align = \"right\" valign=\"top\"><a name=\"" + id + "\"/>" + id + "</td>"   + "\n" +
                  "    <td align = \"left\">"                                                          + "\n" +
                       variants                                                                        +
                  "    </td>"                                                                          + "\n" +
                  "    <td align = \"right\" valign=\"top\">" + f.format(c) + "</td>"                  + "\n" +
                  "    <td align = \"right\" valign=\"top\">" + rank + "</td>"                         + "\n" +
                  "  </tr>"                                                                            + "\n");
  }
  rs.close();

  output.append("</table>" + "\n" +
                "<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>" + "\n" +
                "<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>" + "\n" +
                "<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>" + "\n" +
                "<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>" + "\n" +
                "<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>" + "\n" +
                "</body>"  + "\n" +
                "</html>"  + "\n");

  if (mixup.length() > 2)
  {
    for (int i = 1; i <= id; i++) mixup.append("defSpanType term =: ... [ @term_" + i + " ] ...;\n");
    mixup.append("defSpanType token =term: ... [ any ] ... ;");
  }
 
  writeToFile(output.toString(),   "../out/output.html");
  writeToFile(termlist.toString(), "../out/output.txt");
  writeToFile(mixup.toString(),    "../out/output.mixup");
  writeToFile(csv.toString(),      "../out/output.csv");

  stmt.close();
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- separate tokens as they are understood in mixup
// ----------------------------------------------------------
private static String mixupTokens(String txt)
{
  // --- separate hyphen, apostrophe and slash
  txt = txt.replaceAll("\\-", " - ");
  txt = txt.replaceAll("\\.", " . ");
  txt = txt.replaceAll("'", " ' ");
  txt = txt.replaceAll("/", " / ");

  // --- separate digits from other characters
  char after = ' ';
  for (int i = txt.length() - 1; i >= 0; i--)
  {
    char before = txt.charAt(i);

    if (!Character.isWhitespace(before) && !Character.isWhitespace(after))
    {
      if (Character.isDigit(after))
      {
        // --- K9 -> K 9
        if (!Character.isDigit(before)) txt = txt.substring(0, i+1) + " " + txt.substring(i+1);
      }
      else // --- if (!Character.isDigit(after))
      {
        // --- 4square -> 4 square
        if (Character.isDigit(before))  txt = txt.substring(0, i+1) + " " + txt.substring(i+1);
      }
    }

    after = before;
  }

  // --- remove extra white spaces
  while (txt.length() > (txt.replaceAll("  ", " ")).length()) txt = txt.replaceAll("  ", " ");

  return txt;
}
// ----------------------------------------------------------


// ---------------------------------------------------------------
// --- sort words in a string alphabetically;
//     this is used when term candidates are re-normalised 
//     after acronym expansion
// ---------------------------------------------------------------
private static String sortString(String string)
{
  Set<String> set = new HashSet<String>(Arrays.asList(string.split("\\s+")));
  String[] token = set.toArray(new String[set.size()]);
  Arrays.sort(token);

  StringBuilder sb = new StringBuilder();
  for(String s:token){sb.append(s); sb.append(" ");}
  return sb.toString().trim();
}
// ---------------------------------------------------------------


// ----------------------------------------------------------
// --- term occurrence annotation
// ----------------------------------------------------------
public static void annotate() throws Exception
{
  Logger.debug("Annotating term occurrences...");

  Statement stmt  = con.createStatement();
  Statement stmt1 = con.createStatement();
  Statement stmt2 = con.createStatement();
  ResultSet rs, rs1, rs2;
  String    query;

  // --- delete previous mixup labels
  String labels = "../out/text.labels";
  File file = new File(labels);
  if (file.exists()) file.delete();

  File input = new File("../text");
  if (input.isDirectory())
  {
    // --- Call MinorThird library to perform MixUp

    //Prepare MixUp args
    String[] mixup_args = new String[6];
    mixup_args[0] = "-labels";
    mixup_args[1] = "../text";
    mixup_args[2] = "-mixup"; 
    mixup_args[3] = "../out/output.mixup";
    mixup_args[4] = "-saveAs";
    mixup_args[5] = "../out/text.labels";

    CommandLineUtil.GUIParams gui=new CommandLineUtil.GUIParams();
    CommandLineUtil.BaseParams base=new CommandLineUtil.BaseParams();
    CommandLineUtil.SaveParams save=new CommandLineUtil.SaveParams();
    CommandLineUtil.MixupParams mixup=new CommandLineUtil.MixupParams();
    CommandLineUtil.AnnotatorOutputParams output_mixup = new CommandLineUtil.AnnotatorOutputParams();
    CommandLineProcessor clp = new JointCommandLineProcessor(new CommandLineProcessor[]{gui,base,save,mixup,output_mixup});
    clp.processArguments(mixup_args);

    // Load mixup program
    MixupProgram program=null;
    try{
	program=new MixupProgram(new File(mixup.fileName));
    }catch(Mixup.ParseException ex){
		System.out.println("can't parse file "+mixup.fileName+": "+ex);
    }catch(IOException ex){
		System.out.println("can't load file "+mixup.fileName+": "+ex);
    }

    if(program==null){
	Logger.debug("Cannot runMixup unless a valid mixup program is specified.");
	return;
    }

    // Run Mixup
    MixupInterpreter interpreter=new MixupInterpreter(program);
    TextLabels annotatedLabels=new NestedTextLabels(base.labels);
    interpreter.eval((NestedTextLabels)annotatedLabels);

    // --- import mixup labels into database: output_label
    BufferedReader br = new BufferedReader(new StringReader(
	 new TextLabelsLoader().printTypesAsOps(annotatedLabels)));

    String line;
    while ((line = br.readLine()) != null)
    {
      if (line.indexOf("addToType") == 0)
      {
        // --- parse the mixup line
        line = line.replaceAll("addToType ", "");

        String label = line.substring(line.lastIndexOf(" ") + 1);
        line = line.substring(0, line.lastIndexOf(" "));

        String offset = line.substring(line.lastIndexOf(" ") + 1);
        line = line.substring(0, line.lastIndexOf(" "));

        String start = line.substring(line.lastIndexOf(" ") + 1);
        line = line.substring(0, line.lastIndexOf(" "));

        String doc_id = line;

        // --- import mixup line into database
        if (! (label.equals("term") || label.equals("token")))
        {
          label = label.replace("term_", "");

          int extension = doc_id.lastIndexOf('.');
          if (extension > 0) doc_id = doc_id.substring(0, extension);

          query = "INSERT INTO output_label(doc_id, start, offset, label)"                       + "\n" +
                  "VALUES('" + doc_id + "', " + start + ", " + offset + ", " + label + ");"      + "\n";
          Logger.debug(query);
          stmt.execute(query);
        }
      }
    }
    br.close();

    // --- delete nested labels
    query = "DELETE FROM output_label WHERE rowid IN"                                            + "\n" +
            "("                                                                                  + "\n" +
            "  SELECT T2.rowid"                                                                  + "\n" +
            "  FROM   output_label T1, output_label T2"                                          + "\n" +
            "  WHERE  T1.doc_id = T2.doc_id"                                                     + "\n" +
            "  AND    T1.start <= T2.start"                                                      + "\n" +
            "  AND    T2.start + T2.offset <= T1.start + T1.offset"                              + "\n" +
            "  AND    (T1.start != T2.start OR T2.start + T2.offset != T1.start + T1.offset)"    + "\n" +
            ");"                                                                                 + "\n";
    Logger.debug(query);
    stmt.execute(query);

    // --- delete overlapping labels
    query = "DELETE FROM output_label WHERE rowid IN"                                             + "\n" +
            "("                                                                                   + "\n" +
            "  SELECT T2.rowid"                                                                   + "\n" +
            "  FROM   output_label T1, output_label T2"                                           + "\n" +
            "  WHERE  T1.doc_id = T2.doc_id"                                                      + "\n" +
            "  AND    T2.start <= T1.start + T1.offset"                                           + "\n" +
            "  AND    T1.start + T1.offset <= T2.start + T2.offset"                               + "\n" +
            "  AND    (T1.start != T2.start OR T2.start + T2.offset != T1.start + T1.offset)"     + "\n" +
            ");"                                                                                  + "\n";
    Logger.debug(query);
    stmt.execute(query);

    // --- annotate documents
    query = "SELECT id, verbatim FROM data_document;";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next())
    {
      String id       = rs.getString(1);
      String document = rs.getString(2);

      // --- remove XML "tags" because MinorThird ignores them when labelling documents;
      // --- check method filterXML at
      //     https://github.com/TeamCohen/MinorThird/blob/cdf1a25fa1e2078c5a57aacda1cea54d2ad85b6e/apps/email/MinorTagger.java
      document = document.replaceAll("\\</?([^\\<\\>]{1,50})\\>", "");

      int len = document.length();

      query = "SELECT start, offset, label"     + "\n" +
              "FROM   output_label"             + "\n" +
              "WHERE  doc_id = '" + id + "'"    + "\n" +
              "ORDER BY start DESC;"            + "\n";
      Logger.debug(query);
      rs1 = stmt1.executeQuery(query);
      while (rs1.next())
      {
        int start = rs1.getInt(1);
        int end   = start + rs1.getInt(2);
        int label = rs1.getInt(3);

        if (end <= len) // --- safety check because of removing XML "tags"
        {
          String term = document.substring(start, end).replaceAll("'", " '").replaceAll("\\s+", " ").trim().toLowerCase();

          query = "SELECT id"                                          + "\n" +
                  "FROM   output_table"                                + "\n" +
                  "WHERE  variant LIKE " + fixApostrophe(term) + ";"   + "\n";
          rs2 = stmt2.executeQuery(query);
          if (rs2.next())
          {
            document = document.substring(0, start) + "<a href=\"output.html#" + label + "\">" + document.substring(start, end) + "</a>" + document.substring(end);
          }
          else
          {
            Logger.debug("WARNING: Unable to fully annotate document " + id + ".");
            Logger.debug("Check document for text that looks like an XML tag, e.g. <blah blah blah>.");
            Logger.debug("TERM: " + term);
          }
          rs2.close();
        }
        else
        {
          Logger.debug("WARNING: Unable to fully annotate document " + id + ".");
          Logger.debug("Check document for text that looks like an XML tag, e.g. <blah blah blah>.");
        }
      }
      rs1.close();

      query = "INSERT INTO output_html(id, document)"                    + "\n" +
              "VALUES('" + id + "', " + fixApostrophe(document) + ");"   + "\n";
      Logger.debug(query);
      stmt1.execute(query);
    }
    rs.close();

    String output = "<html>"                                      + "\n" +
                    "<head>"                                      + "\n" +
                    "  <title>FlexiTerm annotations</title>"      + "\n" +
                    "</head>"                                     + "\n" +
                    "<body>"                                      + "\n";

    query = "SELECT id, document"   + "\n" +
            "FROM   output_html"    + "\n" +
            "ORDER BY id;"          + "\n";
    Logger.debug(query);
    rs = stmt.executeQuery(query);
    while (rs.next())
    {
      output += "<p>\n" + rs.getString(2) + "</p>\n";
      output += "\n<hr/>\n\n";
    }
    rs.close();

    output +=  "</body>"  + "\n" +
               "</html>"  + "\n";

    writeToFile(output, "../out/text.html");
  }

  stmt.close();
  stmt1.close();
}
// ----------------------------------------------------------


// ************************* ACRONYMS ****************************

/**
 * The rest of the code represents a modified version of a simple 
 * algorithm for extraction of abbreviations and their definitions 
 * from biomedical text.
 *
 * @see <a href="http://biotext.berkeley.edu/papers/psb03.pdf">A 
 * Simple Algorithm for Identifying Abbreviation Definitions in Biomedical Text</a> 
 * A.S. Schwartz, M.A. Hearst; Pacific Symposium on Biocomputing 8:451-462(2003) 
 * for a detailed description of the algorithm.  
 *
 * @author Ariel Schwartz
 * @version 03/12/03
 * @updated 07/20/16 by Marti Hearst to include BSD License (see below).
 */

/**
 * Copyright (c) 2003, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions 
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the
 *   distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
**/

// ---------------------------------------------------------------
// --- valid short form has to:
//     (1) contain a letter
//     (2) start with a letter, digit or (
// ---------------------------------------------------------------
private static boolean isValidShortForm(String str)
{
  return (hasLetter(str) && (Character.isLetterOrDigit(str.charAt(0)) || (str.charAt(0) == '(')));
}
// ---------------------------------------------------------------


// ---------------------------------------------------------------
// --- str contains a letter?
// ---------------------------------------------------------------
private static boolean hasLetter(String str)
{
  for (int i=0; i < str.length() ; i++) if (Character.isLetter(str.charAt(i))) return true;

  return false;
}
// ---------------------------------------------------------------


// ---------------------------------------------------------------
// --- str contains an UPPERCASE letter?
// ---------------------------------------------------------------
private static boolean hasCapital(String str)
{
  for (int i = 0; i < str.length() ; i++) if (Character.isUpperCase(str.charAt(i))) return true;

  return false;
}
// ---------------------------------------------------------------


// ---------------------------------------------------------------
// --- extract acronym & definition pairs
// ---------------------------------------------------------------
private static void extractPairs(String sentence) throws Exception
{
  String  str;
  String  acronym = "", definition = "";
  int     o, c = -1; // --- open/close parenthesis index
  int     cutoff, nextc, tmp = -1;

  o = sentence.indexOf(" ("); // --- find open parenthesis

  do
  {
    if (o > -1)
    {
      o++; // --- skip white space, i.e. " (" -> "("

      // --- extract candidates for acronym & definition
      if ((c = sentence.indexOf(')', o)) > -1) // --- find closed parenthesis
      {
        // --- find the start of the previous clause based on punctuation
        cutoff = Math.max(sentence.lastIndexOf(". ", o), sentence.lastIndexOf(", ", o));
        if (cutoff == -1) cutoff = -2;

        definition   = sentence.substring(cutoff + 2, o);
        acronym = sentence.substring(o + 1, c);
      }
    }

    if (acronym.length() > 0 || definition.length() > 0) // --- candidates successfully instantiated above
    {
      if (acronym.length() > 1 && definition.length() > 1)
      {
        // --- look for parentheses nested in the candidate acronym
        if ((acronym.indexOf('(') > -1) && ((nextc = sentence.indexOf(')', c + 1)) > -1))
        {
          acronym = sentence.substring(o + 1, nextc);
          c = nextc;
        }

        // --- if separator found within parentheses, then trim everything after it
        if ((tmp = acronym.indexOf(", ")) > -1) acronym = acronym.substring(0, tmp);
        if ((tmp = acronym.indexOf("; ")) > -1) acronym = acronym.substring(0, tmp);

        StringTokenizer tokenizer = new StringTokenizer(acronym);
        if (tokenizer.countTokens() > 2 || acronym.length() > definition.length())
        {
          // --- definition found within (***)

          // --- extract the last token before "(" as a candidate for acronym
          tmp = sentence.lastIndexOf(" ", o - 2);
          str = sentence.substring(tmp + 1, o - 1);

          // --- swap acronym & definition
          definition = acronym;
          acronym = str;

          // --- validate new acronym
          if (!hasCapital(acronym)) acronym = ""; // --- delete invalid acronym
        }

        if (isValidShortForm(acronym)) matchPair(acronym.trim(), definition.trim());
      }

      // --- prepare to process the rest of the sentence after ")"
      sentence = sentence.substring(c + 1);
    }
    else if (o > -1) sentence = sentence.substring(o + 1); // --- process the rest of the sentence

    acronym = "";
    definition = "";
  }
  while ((o = sentence.indexOf(" (")) > -1);

  return;
}
// ---------------------------------------------------------------


// ---------------------------------------------------------------
// --- extract best long form from the candidate definition
// ---------------------------------------------------------------
private static String bestLongForm(String acronym, String definition)
{
  // --- go through the acronym & definition character by character,
  //     right to left looking for a match
  int a =    acronym.length() - 1;
  int d = definition.length() - 1;

  for ( ; a >= 0; a--)
  {
    char c = Character.toLowerCase(acronym.charAt(a));

    if (Character.isLetterOrDigit(c))
    {
      while (
              (d >= 0 && Character.toLowerCase(definition.charAt(d)) != c) ||
              (a == 0 && d > 0 && Character.isLetterOrDigit(definition.charAt(d-1)))
            ) d--;

      if (d < 0) return null;

      d--;
    }
  }

  d = definition.lastIndexOf(" ", d) + 1;
  return definition.substring(d);
}
// ---------------------------------------------------------------


// ---------------------------------------------------------------
// --- check if acronym and definition match
// ---------------------------------------------------------------
private static void matchPair(String acronym, String definition) throws Exception
{
  // --- acronym has to have at least 2 characters
  if (acronym.length() < 2) return;

  String bestLongForm = bestLongForm(acronym, definition);

  if (bestLongForm == null) return;

  StringTokenizer tokenizer = new StringTokenizer(bestLongForm, " \t\n\r\f-");

  int t = tokenizer.countTokens(); // --- number of tokens in the definition
  int c = acronym.length();        // --- number of characters in the acronym

  for (int i = c-1; i >= 0; i--) if (!Character.isLetterOrDigit(acronym.charAt(i))) c--;

  if (bestLongForm.length() < acronym.length() || 
      bestLongForm.indexOf(acronym + " ") > -1 ||
      bestLongForm.endsWith(acronym)           ||
      t > 2*c || t > c+5 || c > 10) return;

  Logger.debug(acronym + "\t" + bestLongForm);

  String query = "INSERT INTO tmp_acronym(acronym, definition)"                                  + "\n" +
                 "VALUES (" + fixApostrophe(acronym) + ", " + fixApostrophe(bestLongForm) + ");"   + "\n";
  Statement stmt  = con.createStatement();
  stmt.execute(query);
}
// ---------------------------------------------------------------

// *********************** END OF ACRONYMS *************************


}
