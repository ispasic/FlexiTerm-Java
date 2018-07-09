
rm -f ../out/output*.*
rm -f ../out/text.html
rm -f ../out/text.labels
rm -f ../out/log.txt

java -Xmx1000M -cp ../bin:../lib/edu.mit.jwi_2.1.5.jar:../lib/jazzy-core.jar:../lib/sqlite-jdbc-3.8.11.2.jar:../lib/stanford-corenlp-2010-11-12.jar:../lib/stanford-postagger.jar:../lib/tinylog-1.3.5.jar:../lib/m3rd_20080611.jar FlexiTerm
