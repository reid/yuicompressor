package com.yahoo.platform.yui.compressor;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

import java.io.*;

public class CompressorTask extends Task {

    private String options;
    private File input;
    private File output;

    public void setInput (File input) {
        this.input = input;
    }

    public void setOutput (File output) {
        this.output = output;
    }

    public void setOptions (String options) {
        this.options = options;
    }

    public void execute () throws BuildException {
        Configuration config;
        try {
            config = new Configuration(options.split(" "));
        } catch (ConfigurationException ex) {
            throw new BuildException(ex.getMessage(), ex);
        }

        String charset = config.getCharset();

        File input = this.input;
        File output = this.output;

        String inputFilename = input.getName();

        int idx = inputFilename.lastIndexOf('.');
        if (idx >= 0 && idx < inputFilename.length() - 1) {
             try {
                config.setInputType(inputFilename.substring(idx + 1));
             } catch (ConfigurationException ex) {
                 throw new BuildException(ex.getMessage(), ex);
             }
        }

        System.out.println("Compressing " + input.getAbsolutePath());

        try {

            Reader in;
            Writer out;
            CompressorErrorReporter reporter = null;
            ConfigurableCompressor compressor;

            in = new InputStreamReader(new FileInputStream(input), charset);

            if (config.isCss()) {

                compressor = new CssCompressor(in);

            } else {

                reporter = new CompressorErrorReporter();
                compressor = new JavaScriptCompressor(in, reporter);

            }

            // Close the input stream first, and then open the output stream,
            // in case the output file should override the input file.
            in.close();
            out = new OutputStreamWriter(new FileOutputStream(output), config.getCharset());

            compressor.compress(out, config);

            if (reporter != null) {
                for (String error : reporter.getErrors()) {
                    System.out.println(error);
                }
                for (String warning : reporter.getWarnings()) {
                    System.out.println(warning);
                }
            }

            out.close();

        } catch (IOException ex) {
            throw new BuildException(ex.getMessage(), ex);
        }

        System.out.println("Compressed to " + output.getAbsolutePath());
    }

}
