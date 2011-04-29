package com.yahoo.platform.yui.compressor;

import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.BuildException;

import org.apache.tools.ant.types.Mapper;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.ResourceCollection;
import org.apache.tools.ant.types.resources.FileProvider;
import org.apache.tools.ant.types.resources.FileResource;

import org.apache.tools.ant.util.FileNameMapper;
import org.apache.tools.ant.util.IdentityMapper;

import java.io.*;

import java.util.Vector;
import java.util.Iterator;


public class CompressorTask extends Task {

    private String options = "";
    private File input;
    private File output;
    private Mapper mapperElement = null;
    private Vector rcs = new Vector();
    
    public void setInput (File input) {
        this.input = input;
    }

    public void setOutput (File output) {
        this.output = output;
    }

    public void setOptions (String options) {
        this.options = options;
    }
    
    //add a collection of resources to copy
    public void add(ResourceCollection res) {
        rcs.add(res);
    }
    
    //mapper takes source files & converts them to dest files
    public Mapper createMapper() throws BuildException {
        if (mapperElement != null) {
            throw new BuildException("Cannot define more than one mapper", getLocation());
        }
        mapperElement = new Mapper(getProject());
        return mapperElement;
    }
    
    //support multiple types of filename mappers being added
    public void add(FileNameMapper fileNameMapper) {
        createMapper().add(fileNameMapper);
    }
    
    //returns the mapper to use based on nested elements, defaults to IdentityMapper
    private FileNameMapper getMapper() {
        FileNameMapper mapper = null;
        if (mapperElement != null) {
            mapper = mapperElement.getImplementation();
        } else {
            mapper = new IdentityMapper();
        }
        return mapper;
    }
    
    //ensure that attributes are legit
    protected void validateAttributes() throws BuildException {
        if(this.rcs == null || this.rcs.size() == 0) {
            if (this.input == null || !this.input.exists()) {
                throw new BuildException("Must specify an input file or at least one nested resource", getLocation());
            }
            
            if(this.output == null) {
                throw new BuildException("Must specify an output file or at least one nested resource", getLocation());
            }
        }
    }
    
    //run the task
    public void execute () throws BuildException {
        validateAttributes();
        
        //set up config
        Configuration config;
        try {
            config = new Configuration(options.split(" "));
        } catch (ConfigurationException ex) {
            throw new BuildException(ex.getMessage(), ex);
        }
        
        
        if(input != null && input.exists()) {
            compress(this.input, this.output, config);
        }
        
        FileNameMapper mapper = getMapper();
        
        for(Iterator it = this.rcs.iterator(); it.hasNext();) {
            ResourceCollection rc = (ResourceCollection) it.next();
            
            for(Iterator rcit = rc.iterator(); rcit.hasNext();) {
                Resource r = (Resource) rcit.next();
                File in = ((FileProvider) r.as(FileProvider.class)).getFile();
                
                String[] mapped = mapper.mapFileName(r.getName());
                if (mapped != null && mapped.length > 0) {
                    for(int k = 0; k < mapped.length; k++) {
                        File out = getProject().resolveFile(in.getParent() + File.separator + mapped[k]);
                        
                        compress(in, out, config);
                    }
                }
            }
        }
    }
    
    //do the compression dance
    private void compress(File input, File output, Configuration config) throws BuildException {
        String inputFilename = input.getName();
        String charset = config.getCharset();

        int idx = inputFilename.lastIndexOf('.');
        if (idx >= 0 && idx < inputFilename.length() - 1) {
             try {
                config.setInputType(inputFilename.substring(idx + 1));
             } catch (ConfigurationException ex) {
                 throw new BuildException(ex.getMessage(), ex);
             }
        }

        log("Compressing " + input.getAbsolutePath(), Project.MSG_VERBOSE);

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
                    log(error);
                }
                for (String warning : reporter.getWarnings()) {
                    log(warning);
                }
            }

            out.close();

        } catch (IOException ex) {
            throw new BuildException(ex.getMessage(), ex);
        }

        log("Compressed to " + output.getAbsolutePath(), Project.MSG_VERBOSE);
    }
}