## [FlexiTerm](http://users.cs.cf.ac.uk/I.Spasic/flexiterm/): a software tool to automatically recognise multi-word terms in text documents.

FlexiTerm takes as input a corpus of ASCII documents and outputs a ranked list of automatically recognised multi-word terms.

If you use FlexiTerm in your work/research, please cite the following paper:

[Spasić, I., Greenwood, M., Preece, A., Francis, N., & Elwyn, G. (2013). FlexiTerm: a flexible term recognition method. Journal of biomedical semantics, 4(1), 27.](https://jbiomedsem.biomedcentral.com/articles/10.1186/2041-1480-4-27)

For more information, please visit the [FlexiTerm](http://users.cs.cf.ac.uk/I.Spasic/flexiterm/) website.

Software requirements to run FlexiTerm:

* Java version "1.8.0" or above

Build created with:

* OpenJDK Runtime Environment (AdoptOpenJDK)(build 1.8.0_202-b08)
* OpenJDK 64-Bit Server VM (AdoptOpenJDK)(build 25.202-b08, mixed mode)

How to run FlexiTerm:

1. Place plain text files into "text" folder.
2. OPTIONAL: Replace file stoplist.txt in "resources" folder with your own, if needed.
3. Run FlexiTerm.bat (Windows) or FlexiTerm.sh (Unix/Linux) at "script" folder from the command line.
4. Check results in "out" folder. They will be presented in different formats: txt, csv and html.

How to build FlexiTerm:

```console
javac -cp lib/edu.mit.jwi_2.1.5.jar:lib/jazzy-core.jar:lib/m3rd_20080611.jar:lib/sqlite-jdbc-3.8.11.2.jar:lib/stanford-corenlp-2010-11-12.jar:lib/stanford-postagger.jar:lib/tinylog-1.3.5.jar src/FlexiTerm.java src/FlexiClustering.java src/Porter.java src/WordNet.java -d bin
```

You can find more information about how to use or to extend FlexiTerm in the [Wiki](https://github.com/ulopeznovoa/FlexiTerm/wiki) page associated to this repository.
