## FlexiTerm: a software tool to automatically recognise multi-word terms in text documents.

FlexiTerm takes as input a corpus of ASCII documents and outputs a ranked list of automatically recognised multi-word terms.

If you use FlexiTerm in your work/research, please cite the following paper:

[Spasić, I., Greenwood, M., Preece, A., Francis, N., & Elwyn, G. (2013). FlexiTerm: a flexible term recognition method. Journal of biomedical semantics, 4(1), 27.](https://jbiomedsem.biomedcentral.com/articles/10.1186/2041-1480-4-27)

Software requirements to run FlexiTerm:

* Java version "1.6.0" or above
* Java(TM) SE Runtime Environment (build 1.6.0-b105)
* Java HotSpot(TM) Client VM (build 1.6.0-b105, mixed mode)

How to run FlexiTerm:

1. Place plain text files into "text" folder.
2. OPTIONAL: Replace file stoplist.txt in "resources" folder with your own, if needed.
3. Run FlexiTerm.bat (Windows) or FlexiTerm.sh (Unix/Linux) at "script" folder from the command line.
4. Check results in "out" folder. They will be presented in different formats: txt, csv and html.

Folder structure:

* bin: Binary (Java .class) files
* config: Contains "settings.txt" file with configuration options for FlexiTerm
* lib: External libraries required by FlexiTerm
* out: Output files
* resources: Contains text resources required by FlexiTerm, including
  * resources/dict: WordNet files used by WordNet.java
  * resources/models: Models used by the Stanford CoreNLP.
  * resources/stoplist.txt     : Stoplist used to filter out stopwords.
  * resources/dictionary.txt   : A list of distinct tokens used as a dictionary by Jazzy to suggest similar tokens.
* script: Windows/Unix scripts to run FlexiTerm
* src: Source (.java) files
* text: Input text files.

Output files format:

* output.csv       : A table of results: Rank | Term | Score | Frequency
* output.html      : A table of results: ID | Term variants | Score | Rank
* output.txt       : A list of recognised term variants ordered by their scores.
* output.mixup     : A Mixup file used by MinorThird to annotate term occurrences in text.
* text.html        : Input text annotated with occurrences of terms listed in output.html.
* log.txt    : Listing output used for debugging.

Format of configuration file settings.txt:
* term pattern(s)
* stoplist
* edit distance threshold
* minimum term candidate frequency
* minimum (implicit) acronym frequency
* acronym recognition mode

Default parameters in settings.txt:

* pattern = "(((((NN|JJ) )*NN) IN (((NN|JJ) )*NN))|((NN|JJ )*NN POS (NN|JJ )*NN))|(((NN|JJ) )+NN)"
* max = 3               : Jazzy distance treshold: How many operations away? Reduce for better similarity.
* min = 2               : Term frequency threshold: occurrence > min. Increase for better precision.
* MIN = 9               : Implicit acronym frequency threshold: occurrence > min. Increase for better precision
* acronyms = explicit   : Acronyms have to be explicitly defined in text using parentheses.

### FlexiClustering

OPTIONAL: FlexiClustering takes as input list of multi-word terms recognised by FlexiTerm and performs hierarchical clustering of these terms.

To run FlexiClustering *after* you have run FlexiTerm:

1. Run FlexiClustering.bat from the command line.
2. Check results by uploading output_dendrogram.txt to [evolgenius](http://www.evolgenius.info/evolview) (an online tree viewer).

Files in the folder structure related to FlexiClustering:

* script/FlexiClustering.bat: Batch file that runs FlexiClustering.java.
* out/output_agglomeration.csv: Cluster agglomeration schedule:
                           cluster1 cluster2 cluster1+2 distance
* out/output_dendrogram.txt: Main clustering results - a dendrogram in the Newick format:
                           https://en.wikipedia.org/wiki/Newick_format
* out/output_distance.csv: Term distance matrix, which you may use to perform clustering 
                           with a tool of your own choice, e.g. SPSS.
* out/FlexiClustering.log: Listing output used for debugging.
* src/FlexiClustering.java: Main Java class.
