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

import java.io.*;
import java.util.*;
import java.sql.*;
/*
// --- import Stanford NLP classes
import edu.stanford.nlp.ling.Sentence;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.WordTag;
import edu.stanford.nlp.ling.WordLemmaTag;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import edu.stanford.nlp.process.Morphology;

// --- for parsing
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.ling.*;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.semgraph.*;
import edu.stanford.nlp.util.*;
*/
public class FlexiClustering {

// --- (global) database connection variables ---
private static Connection con;
private static String driver = "org.sqlite.JDBC";
private static String url = "jdbc:sqlite:flexiterm.sqlite";

// --- linguistic pre-processing --- ?? delete?
//private static MaxentTagger tagger;
//private static WordNet wordnet;
//private static Porter porter = new Porter();

// --- (global) clustering variables
private static int size;                    // --- size of the distance matrix
private static int count = 0;               // --- counts non-singleton clusters
private static double[][] inputMatrix;      // --- distance between individual elements
private static double[][] distanceMatrix;   // --- distance between the current clusters
private static Cluster[]  cluster;          // --- current clusters
private static String[]   node;             // --- dendrogram nodes
private static StringBuffer agglomeration   // --- agglomeration schedule
                    = new StringBuffer("");
private static HashMap map;

private static final List<String> preposition = Arrays.asList(
  "about",
  "above",
  "across",
  "after",
  "against",
  "along",
  "among",
  "around",
  "at",
  "before",
  "behind",
  "between",
  "beyond",
  "by",
  "despite",
  "during",
  "except",
  "following",
  "for",
  "from",
  "in",
  "including",
  "into",
  "near",
  "of",
  "off",
  "on",
  "out",
  "over",
  "since",
  "through",
  "throughout",
  "to",
  "towards",
  "under",
  "until",
  "up",
  "upon",
  "with",
  "within",
  "without");


public static void main(String[] args)
{
  try
  {
    open();  // --- SQLite database connection

    Statement stmt  = con.createStatement();
    String query;

    stmt.execute("PRAGMA journal_mode = OFF;");

    // --- empty relevant database tables
    query = "DELETE FROM vector_space;"; stmt.execute(query);
    query = "DELETE FROM vector_row;"; stmt.execute(query);
    query = "DELETE FROM vector_feature;"; stmt.execute(query);
    query = "DELETE FROM vector_name;"; stmt.execute(query);

    // --- generate feature vectors and distance matrix
    term2vector();

    readDistanceMatrix("./output_distance.csv");

    // --- initialise global clustering variables
    map     = new HashMap(3 * size);
    cluster = new Cluster[size];
    node    = new String[size];
    for (int i = 0; i < size; i++)
      cluster[i] = new Cluster(i);  // --- start with singleton clusters

    // --- perform clustering
    clustering();

    // --- print agglomeration schedule
    writeToFile(agglomeration.toString(), "./output_agglomeration.csv");

    // --- print dendrogram in Newick format
    //     https://en.wikipedia.org/wiki/Newick_format
    writeToFile(node[count] + ";", "./output_dendrogram.txt");

    con.commit();
    stmt.close();

    close();  // --- SQLite database connection
  }
  catch (SQLException ex) {explain(ex);}
  catch (Exception e)     {e.printStackTrace();}
}

// ********************** END OF MAIN ***********************


// ----------------------------------------------------------
// --- convert terms to feature vectors and calculate distance
// ----------------------------------------------------------
public static void term2vector()
{
  try
  {
    Statement stmt  = con.createStatement();
    Statement stmt1 = con.createStatement();
    ResultSet rs, rs1;
    String    query;

/*
    Properties props = new Properties();
    props.setProperty("annotators", "tokenize,ssplit,pos,parse");
    StanfordCoreNLP pipeline = new StanfordCoreNLP(props);
    edu.stanford.nlp.pipeline.Annotation document;

    document = new edu.stanford.nlp.pipeline.Annotation("The patients with hepatitis C .");
    pipeline.annotate(document);
    for (CoreMap sentence : document.get(CoreAnnotations.SentencesAnnotation.class))
    {
      Tree constituencyParse = sentence.get(TreeCoreAnnotations.TreeAnnotation.class);
      System.out.println(constituencyParse);
      SemanticGraph dependencyParse = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
      System.out.println(dependencyParse.toList());
    }

    document = new edu.stanford.nlp.pipeline.Annotation("The tumor necrosis factor alpha .");
    pipeline.annotate(document);
    for (CoreMap sentence : document.get(CoreAnnotations.SentencesAnnotation.class))
    {
      Tree constituencyParse = sentence.get(TreeCoreAnnotations.TreeAnnotation.class);
      System.out.println(constituencyParse);
      SemanticGraph dependencyParse = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
      System.out.println(dependencyParse.toList());
    }

    document = new edu.stanford.nlp.pipeline.Annotation("The nuclear factor kappa B .");
    pipeline.annotate(document);
    for (CoreMap sentence : document.get(CoreAnnotations.SentencesAnnotation.class))
    {
      Tree constituencyParse = sentence.get(TreeCoreAnnotations.TreeAnnotation.class);
      System.out.println(constituencyParse);
      SemanticGraph dependencyParse = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
      System.out.println(dependencyParse.toList());
    }

    document = new edu.stanford.nlp.pipeline.Annotation("The human factor VIII .");
    pipeline.annotate(document);
    for (CoreMap sentence : document.get(CoreAnnotations.SentencesAnnotation.class))
    {
      Tree constituencyParse = sentence.get(TreeCoreAnnotations.TreeAnnotation.class);
      System.out.println(constituencyParse);
      SemanticGraph dependencyParse = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
      System.out.println(dependencyParse.toList());
    }

System.exit(0);
*/

    // --- get the *total* number of terms
    query = "SELECT MAX(id) FROM output_table;";
    System.out.println(query);
    rs = stmt.executeQuery(query);
    int total = 0;
    if (rs.next()) total = rs.getInt(1);
    rs.close();

    // --- select term representatives as the longest (in tokens) most frequent variant
    for (int i = 1; i <= total; i++) // --- for each term
    {
      String term = "";

      query = "SELECT T.variant,"                                                                         + "\n" +
              "       LENGTH(T.variant) - LENGTH(REPLACE(REPLACE(T.variant, '-', ' '), ' ', '') ) AS L,"  + "\n" +
              "       COUNT(*) AS F"                                                                      + "\n" +
              "FROM   output_table T, term_phrase P"                                                      + "\n" +
              "WHERE  T.id = " + i                                                                        + "\n" +
              "AND    LOWER(T.variant) = LOWER(P.phrase)"                                                 + "\n" +
              "AND    L > 0"                                                                              + "\n" +
              "GROUP BY T.variant, L"                                                                     + "\n" +
              "ORDER BY L DESC, F DESC;"                                                                  + "\n";
      rs = stmt.executeQuery(query);
      if (rs.next())
      {
        term  = rs.getString(1);
        int f = rs.getInt(3);

        // --- if rare, look for the next long term representative
        if (f < 2) if (rs.next()) term = rs.getString(1);

        System.out.println("TERM #" + i + ":\t" + term);
      }
      rs.close();

      if (!term.equals(""))
      {
        query = "INSERT INTO vector_name(id, term)"                  + "\n" +
                "VALUES(" + i + ", " + fixApostrophe(term) +  ");"   + "\n";
        stmt.execute(query);
      }
    }

    if (total > 120) total = 120;    // --- cap the numbers of terms to cluster
    size = total;                    // --- size of the distance matrix

    for (int i = 1; i <= total; i++) // --- for each term
    {
      String term = "";

      query = "SELECT term FROM vector_name WHERE id = " + i + ";";
      rs = stmt.executeQuery(query);
      if (rs.next())
      {
        term = rs.getString(1);
        System.out.println("TERM #" + i + ":\t" + term);
      }
      rs.close();

      if (term.equals("")) size--;
      else
      {
        // --- expand nested short forms
        query = "SELECT TRIM(REPLACE(LOWER(' ' || N1.term   || ' '),"                                       + "\n" +
                "                    LOWER(' ' || T.variant || ' '),"                                       + "\n" +
                "                          ' ' || N2.term   || ' ')) AS long,"                              + "\n" +
                "       LENGTH(T.variant) AS chars,"                                                        + "\n" +
                "       LENGTH(N2.term) - LENGTH(REPLACE(REPLACE(N2.term, '-', ' '), ' ', '') ) AS tokens"  + "\n" +
                "FROM   vector_name N1, output_table T, vector_name N2"                                     + "\n" +
                "WHERE  N1.id = " + i                                                                       + "\n" +
                "AND    ' ' || N1.term || ' ' LIKE '% ' || T.variant || ' %'"                               + "\n" +
                "AND    N1.id != T.id"                                                                      + "\n" +
                "AND    LENGTH(T.variant) <= 10"                                                            + "\n" +
                "AND    LENGTH(N2.term) > 15"                                                               + "\n" +
                "AND    T.id = N2.id"                                                                       + "\n" +
                "AND    long <> N1.term"                                                                    + "\n" +
                "ORDER BY chars DESC, tokens DESC;"                                                         + "\n";
        stmt.executeQuery(query);
        if (rs.next())
        {
          term = rs.getString(1);
          System.out.println("TERM #" + i + ":\t" + term);
        }
        rs.close();

        query = "INSERT INTO vector_row(id, term)"                   + "\n" +
                "VALUES(" + i + ", " + fixApostrophe(term) +  ");"   + "\n";
        stmt.execute(query);

        term = " !!! " + term + " "; // --- add token delimiters at the start and end + left boundary

        // --- remove possessives, hyphens, punctuation, 'like' and numbers
        term = term.replaceAll(" 's", "");
        term = term.replaceAll("'", " ");
        term = term.replaceAll(",", " ");
        term = term.replaceAll("-[a-z]+ed ", " ");  // --- -based, -related, etc.
        term = term.replaceAll("-", " ");
        term = term.replaceAll(" like", " ");
        term = term.replaceAll("[0-9]+", " ");
        term = term.replaceAll(" [iI]{1,3} ", " ");
        term = term.replaceAll(" (iv|IV) ", " ");
        term = term.replaceAll(" [vV] ", " ");
        term = term.replaceAll(" (v|V)[iI]{1,3} ", " ");
        term = term.replaceAll(" (ix|IX) ", " ");
        term = term.replaceAll(" [xX] ", " ");

        /* --- parse document
        document = new edu.stanford.nlp.pipeline.Annotation("Some " + term + " !");
        pipeline.annotate(document);
        for (CoreMap sentence : document.get(CoreAnnotations.SentencesAnnotation.class))
        {
          Tree constituencyParse = sentence.get(TreeCoreAnnotations.TreeAnnotation.class);
          System.out.println(constituencyParse);
          SemanticGraph dependencyParse = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
          System.out.println(dependencyParse.toList());
        }
        */

        // --- bring letter qualifiers to the front, so that
        //     they are treated as the least important modifiers
        term = term.replaceAll("(.*) (alpha|beta|gamma|kappa|omega) (.*)", "$2 $1 $3");
        term = term.replaceAll("(.*) ([a-z]) (.*)", "$2 $1 $3");
        term = term.replaceAll("(.*) (in situ|in vivo|in vitro|in silico) (.*)", "$2 $1 $3");
        term = term.replaceAll("(.*) (ex situ|ex vivo|ex vitro|ex silico) (.*)", "$2 $1 $3");
        term = term.replaceAll("(in|ex) situ",   "situ");
        term = term.replaceAll("(in|ex) vivo",   "vivo");
        term = term.replaceAll("(in|ex) vitro",  "vitro");
        term = term.replaceAll("(in|ex) silico", "silico");

        term = term.replaceAll("\\s{2,}", " ").trim();

        // --- order remaining tokens
        String ordered = "";

        String pp = ""; // --- prepositional phrase

        // --- read tokens from right to left
        String[] token = term.split("\\s");
        int t = token.length;
        while (t > 0)
        {
          t--;

          if (preposition.contains(token[t]))
          {
            // --- hypothesis: token[t-1] is the noun head of the preceding NP

            if (token[t].equals("of"))
            {
              t--;

              // --- prioritise the noun head by pushing it to the last position
              if (t >= 0) ordered = ordered + " " + token[t];
            }
            else
            {
              pp = ordered; // --- save PP to insert before the preceding NP

              t--;

              if (t >= 0) ordered = token[t]; // --- restart ordering from the noun head
              else        ordered = "";
            }
          }
          else if (token[t].equals("!!!"))         // --- if reached the start of the NP?
               ordered =       pp + " " + ordered; // --- insert PP (if any) before main NP
          else ordered = token[t] + " " + ordered; // --- continue going from right to left
        }

        // --- print order from right to left
        System.out.print("ORDER:\t\t");
        ordered = ordered.replaceAll("\\s\\s", " ").trim();
        token = ordered.split("\\s");
        t = token.length;
        int d = 0; // --- step distance from the noun head, i.e. position in the new order
        while (t > 0)
        {
          t--;

          String stem = token[t];

          query = "SELECT stem, COUNT(*) AS f"                          + "\n" +
                  "FROM   data_token"                                   + "\n" +
                  "WHERE  LOWER(token) = " + fixApostrophe(token[t])    + "\n" +
                  "GROUP BY stem"                                       + "\n" +
                  "ORDER BY f DESC;"                                    + "\n";
          rs = stmt.executeQuery(query);
          if (rs.next()) stem = rs.getString(1);
          rs.close();

          query = "INSERT INTO vector_feature(id, stem, d)"                      + "\n" +
                  "VALUES(" + i + ", " + fixApostrophe(stem) + ", " + d + ");"   + "\n";
          stmt.execute(query);

          System.out.print(stem + "[" + d + "] ");

          d++;
        }
        System.out.println("\n");
      }

    } // --- no more terms


    // --- vector space: all stems
    query = "INSERT INTO vector_space(stem)"    + "\n" +
            "SELECT DISTINCT stem"              + "\n" +
            "FROM   vector_feature"             + "\n" +
            "ORDER BY stem;"                    + "\n";
    stmt.execute(query);

    for (int i = 0; i <= total; i++)
    {
      String vector = "";

      query = "SELECT stem FROM vector_space ORDER BY stem;";
      rs = stmt.executeQuery(query);
      while (rs.next())
      {
        double weight = 0.0;

        String stem = rs.getString(1);

        query = "SELECT d"                                 + "\n" +
                "FROM   vector_feature"                    + "\n" +
                "WHERE  id = " + i                         + "\n" +
                "AND    stem = " + fixApostrophe(stem)     + "\n" +
                "ORDER BY d ASC;"                          + "\n";     // --- think Bora[0] Bora[1]
        rs1 = stmt1.executeQuery(query);
        if (rs1.next()) weight = 1.0 / (rs1.getInt(1) + 1.0); // --- weight function @@@
        rs1.close();

        vector += "," + weight;
      }
      rs.close();

      if (vector.length() > 0) vector = vector.substring(1);

      query = "UPDATE vector_row"                   + "\n" +
              "SET    vector = '" + vector + "'"    + "\n" +
              "WHERE  id = " + i + ";"              + "\n";
      stmt.execute(query);
    }

    // --- calculate "cosine" distance matrix
    StringBuffer matrix  = new StringBuffer("");
    StringBuffer vectors = new StringBuffer("");
    String row = "";

    query = "SELECT V1.vector, V2.vector, V1.id, V2.id, V1.term"   + "\n" +
            "FROM   vector_row V1, vector_row V2"                  + "\n" +
            "WHERE  V1.id <= " + total                             + "\n" +
            "AND    V2.id <= " + total                             + "\n" +
            "ORDER BY V1.id, V2.id;"                               + "\n";
    rs = stmt.executeQuery(query);
    while (rs.next())
    {
      String  v1 = rs.getString(1);
      String  v2 = rs.getString(2);
      int    id1 = rs.getInt(3); 
      int    id2 = rs.getInt(4);                    // --- column index in the distance matrix
      String name = "\"" + rs.getString(5) + "\"";  // --- enclose string in quotes for export into CSV

      if (id2 == 1)
      {
        vectors.append(name + "," + v1 + "\n");

        if (!row.equals("")) matrix.append(row + "\n");   // --- end the row
        row = name;                                       // --- prepare for next row
      }

      double distance = 0.0D;

      if (id1 != id2)
      {
        double v1v2 = 0.0D;
        double v1v1 = 0.0D;
        double v2v2 = 0.0D;

        String[] c1 = v1.split(",");
        String[] c2 = v2.split(",");

        for (int k = 0; k < c1.length; k++)
        {
          double value1 = Double.parseDouble(c1[k]);
          double value2 = Double.parseDouble(c2[k]);

          v1v2 += value1 * value2;
          v1v1 += value1 * value1;
          v2v2 += value2 * value2;
        }

        distance = 1.0D - v1v2/(Math.sqrt(v1v1) * Math.sqrt(v2v2));
      }

      row += "," + distance;
    }
    rs.close();
    if (!row.equals("")) matrix.append(row);   // --- append the last row

    writeToFile(matrix.toString(), "./output_distance.csv");
    writeToFile(vectors.toString(), "./output_vectors.csv");
  }
  catch (SQLException ex) {explain(ex);}
  catch(Exception e){e.printStackTrace();}
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- read distance matrix from file
// ----------------------------------------------------------
private static void readDistanceMatrix(String file) throws IOException
{
  BufferedReader reader = new BufferedReader(new FileReader(file));

  inputMatrix = new double[size][size];

  int i = 0;     // --- row index
  String line;

  while ((line = reader.readLine()) != null)
  {
    // --- remove the first cell, which contains the term
    line = line.replaceAll("\".*\",", "");

    String[] row = line.split(",");

    for (int j = 0; j < row.length; j++) inputMatrix[j][i] = Double.parseDouble(row[j]);

    i++;
  }

  reader.close();
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- clustering method: complete linkage (maximum distance)
// ----------------------------------------------------------
private static double linkageComplete(Cluster cluster1, Cluster cluster2, double[][] matrix)
{
  double d = Double.MIN_VALUE;

  Vector vector1 = cluster1.getItems();
  Vector vector2 = cluster2.getItems();

  ListIterator iterator1 = vector1.listIterator();

  while (iterator1.hasNext())
  {
    int i = ((Integer) iterator1.next()).intValue();

    ListIterator iterator2 = vector2.listIterator();

    while (iterator2.hasNext())
    {
      int j = ((Integer) iterator2.next()).intValue();

      if (matrix[i][j] > d) d = matrix[i][j];
    }
  }

  return d;
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- clustering method: single linkage (minimum distance)
// ----------------------------------------------------------
private static double linkageSingle(Cluster cluster1, Cluster cluster2, double[][] matrix)
{
  double d = Double.MAX_VALUE;

  Vector vector1 = cluster1.getItems();
  Vector vector2 = cluster2.getItems();

  ListIterator iterator1 = vector1.listIterator();

  while (iterator1.hasNext())
  {
    int i = ((Integer) iterator1.next()).intValue();

    ListIterator iterator2 = vector2.listIterator();

    while (iterator2.hasNext())
    {
      int j = ((Integer) iterator2.next()).intValue();

      if (matrix[i][j] < d) d = matrix[i][j];
    }
  }

  return d;
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- clustering method: average linkage
// ----------------------------------------------------------
private static double linkageAverage(Cluster cluster1, Cluster cluster2, double[][] matrix)
{
  double d = 0.0D;

  Vector vector1 = cluster1.getItems();
  Vector vector2 = cluster2.getItems();

  double s1 = vector1.size();
  double s2 = vector2.size();

  ListIterator iterator1 = vector1.listIterator();

  while (iterator1.hasNext())
  {
    int i = ((Integer) iterator1.next()).intValue();

    ListIterator iterator2 = vector2.listIterator();

    while (iterator2.hasNext())
    {
      int j = ((Integer) iterator2.next()).intValue();

      d += matrix[i][j];
    }
  }

  return d / (s1 * s2);
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- perform hiererchical clustering
// ----------------------------------------------------------
private static void clustering() throws IOException
{
  int i = 0;
  int j = 0;

  distanceMatrix = new double[size][size];

  for (int k = 0; k < size; k++)
  for (int m = 0; m < size; m++)
  distanceMatrix[k][m] = inputMatrix[k][m];

  while (size > 1)
  {
    double d = Double.MAX_VALUE;

    for (int n = 0; n < size; n++)
    {
      for (int i1 = n + 1; i1 < size; i1++)
      {
        if (distanceMatrix[n][i1] < d)
        {
          i = n;
          j = i1;
          d = distanceMatrix[i][j];
        }
      }
    }

    Cluster cluster1 = cluster[i];
    Cluster cluster2 = cluster[j];

//  System.out.println(cluster1 + " merged with " + cluster2 + " at distance " + d);

    Cluster cluster3 = new Cluster();

    cluster3.addCluster(cluster1);
    cluster3.addCluster(cluster2);

    cluster3.addItems(cluster1);
    cluster3.addItems(cluster2);

    cluster3.setDistance(d);

    dendrogram(cluster1, cluster2, cluster3);

    update(i, j, cluster3);
  }
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- update distance matrix after merging two clusters
// ----------------------------------------------------------
private static void update(int index1, int index2, Cluster cluster3)
{
  HashMap localMap = new HashMap(3 * size);

  size--;

  double[][] newMatrix = new double[size][size];

  Cluster[] newCluster = new Cluster[size];

  int i = 0;
  int j;

  newCluster[i] = cluster3;

  for (j = 0; j <= size; j++)
  {
    if (j == index1 || j == index2) {} // --- skip old clusters merged into new one
    else
    {
      i++;
      newCluster[i] = cluster[j];
      localMap.put(new Integer(i), new Integer(j));
    }
  }

  for (j = 0; j < size; j++)
  {
    for (int k = j+1; k < size; k++)
    {
      if (j == 0) newMatrix[j][k] = linkageComplete(newCluster[j], newCluster[k], inputMatrix);
      else        newMatrix[j][k] = distanceMatrix[((Integer) localMap.get(new Integer(j))).intValue()][((Integer) localMap.get(new Integer(k))).intValue()];
    }
  }

  cluster  = newCluster;
  distanceMatrix = newMatrix;
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- print information about new clusters:
//     cluster1 cluster2 new_cluster distance
// ----------------------------------------------------------
private static void dendrogram(Cluster cluster1, Cluster cluster2, Cluster cluster3) throws IOException
{
  String str1 = (String) map.get(cluster1);
  String str2 = (String) map.get(cluster2);
  String n1 = "", n2 = ""; // --- dendrogram node
  int i1 = 0, i2 = 0;      // --- node index (i.e. in which iteration it was added to dendrogram)

  try
  {
    Statement stmt = con.createStatement();
    ResultSet rs;
    String query;

    if (str1 == null)
    {
      str1 = getID(cluster1);
      map.put(cluster1, str1);

      query = "SELECT term FROM vector_name WHERE id = " + str1 + ";";
      rs = stmt.executeQuery(query);
      if (rs.next())
      {
        String term1 = rs.getString(1);
        term1 = term1.replaceAll(",", "");
        term1 = term1.replaceAll(" ", "_");
        term1 = term1.replaceAll(" '", "'");
        n1 = term1;
      }
      else n1 = str1;
      rs.close();
    }
    else
    {
      i1 = Integer.parseInt(str1.replaceAll("S", ""));
      n1 = node[i1];
    }

    if (str2 == null)
    {
      str2 = getID(cluster2);
      map.put(cluster2, str2);

/*
      query = "SELECT term"                  + "\n" +
              "FROM   vector_row"            + "\n" +
              "WHERE  id = " + str2 + ";"    + "\n";
*/
      query = "SELECT term FROM vector_name WHERE id = " + str2 + ";";
      rs = stmt.executeQuery(query);
      if (rs.next())
      {
        String term2 = rs.getString(1);
        term2 = term2.replaceAll(",", "");
        term2 = term2.replaceAll(" ", "_");
        term2 = term2.replaceAll(" '", "'");
        n2 = term2;
      }
      else n2 = str2;
      rs.close();
    }
    else
    {
      i2 = Integer.parseInt(str2.replaceAll("S", ""));
      n2 = node[i2];
    }

    String str3 = getID(cluster3);
    map.put(cluster3, str3);

    double d = cluster3.getDistance();

    agglomeration.append(str1 + "," + str2 + "," + str3 + "," + d + "\n");

    // --- branch length
    double d1 = d - cluster1.getDistance();
    double d2 = d - cluster2.getDistance();

    // --- round to 4 decimal places
    d1 = Math.round(10000.0*d1)/10000.0;
    d2 = Math.round(10000.0*d2)/10000.0;

//  node[count] = "(" + n1 + ":" + (count-i1) + "," + n2 + ":" + (count-i2) + ")";
    node[count] = "(" + n1 + ":" + d1 + "," + n2 + ":" + d2 + ")";

    stmt.close();
  }
  catch (SQLException ex) {explain(ex);}
  catch(Exception e){e.printStackTrace();}
}
// ----------------------------------------------------------


// ----------------------------------------------------------
// --- get/set cluster's identifier
// ----------------------------------------------------------
private static String getID(Cluster cluster)
{
  if (cluster.items.size() == 1) // --- singleton
  {
    ListIterator iterator = cluster.items.listIterator();
    int i = ((Integer) iterator.next()).intValue() + 1;

    return String.valueOf(i);
  }

  // --- set
  count++;
  return "S" + count;
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
  System.out.println("\nDatabase.open(): open database connection\n");

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
  System.out.println("\nDatabase.close(): close database connection");

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
    System.out.println("SQLState: " + ex.getSQLState());
    System.out.println("Message:  " + ex.getMessage());
    System.out.println("Vendor:   " + ex.getErrorCode());
    System.out.println("");

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

private static class Cluster
{
  private Vector clusters;
  public  Vector items;
  private double distance;

  public Cluster()
  {
    clusters = new Vector();
    items = new Vector();
  }

  public Cluster(int parameter)
  {
    clusters = new Vector();
    addCluster(this);
    items = new Vector();
    addItem(new Integer(parameter));
    setDistance(0.0D);
  }

  public void setDistance(double parameter)
  {
    distance = parameter;
  }

  public double getDistance()
  {
    return distance;
  }

  public Vector getClusters()
  {
    return clusters;
  }

  public void addCluster(Cluster paramCluster)
  {
    clusters.add(paramCluster);
  }

  public Vector getItems()
  {
    return items;
  }

  public void addItem(int parameter)
  {
    items.add(parameter);
  }

  public void addItems(Cluster cluster)
  {
    Vector vector = cluster.getItems();
    ListIterator iterator = vector.listIterator();

    while (iterator.hasNext()) addItem((Integer)iterator.next());
  }

  public String toString()
  {
    Iterator iterator = items.iterator();
    StringBuffer buffer = new StringBuffer();

    while (iterator.hasNext())
    {
      buffer.append(((Integer)iterator.next()).intValue() + 1);
      buffer.append(",");
    }

    return buffer.substring(0, buffer.length() - 1);
  }

}

}