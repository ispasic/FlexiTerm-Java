import java.net.URL;
import java.io.IOException;
import java.io.File;
import java.util.List;

import edu.mit.jwi.*;
import edu.mit.jwi.item.POS;
import edu.mit.jwi.morph.WordnetStemmer;
import edu.mit.jwi.morph.SimpleStemmer;

public class WordNet {

    URL url;
    IDictionary dict;
    SimpleStemmer stemmer;
    WordnetStemmer lemma;
    
    public WordNet(String loc) throws IOException
    {
        url = new URL("file", null, loc);

        dict = new Dictionary(url);
        dict.open();
        stemmer = new SimpleStemmer();
        lemma = new WordnetStemmer(dict);
    }
    
    public String stem(String word)
    {
        List<String> result = stemmer.findStems(word);
        if(result.size()==0) return word;
        else return result.get(0);
    }

    public String stem(String word, POS pos)
    {
        List<String> result = stemmer.findStems(word, pos);
        if(result.size()==0) return word;
        else return result.get(0);
    }

    public String lemmatize(String word, POS pos)
    {
        List<String> result = lemma.findStems(word, pos);
        if(result.size()==0) return word;
        else return result.get(0);
    }

    public POS getPOS(String pos)
    {
        String chars = pos.substring(0, 2).toLowerCase();

             if (chars.equals("vb")) return POS.VERB;
        else if (chars.equals("nn")) return POS.NOUN;
        else if (chars.equals("jj")) return POS.ADJECTIVE;
        else if (chars.equals("rb")) return POS.ADVERB;
        else return null;
    }
}
