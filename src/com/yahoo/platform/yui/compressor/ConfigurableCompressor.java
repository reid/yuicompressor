package com.yahoo.platform.yui.compressor;

import java.io.IOException;
import java.io.Writer;

public interface ConfigurableCompressor {

    public void compress (Writer out, Configuration config) throws IOException;

}
