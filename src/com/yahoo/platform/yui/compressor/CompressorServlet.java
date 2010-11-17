package com.yahoo.platform.yui.compressor;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

import java.io.*;
import java.util.LinkedList;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONString;
import org.json.JSONStringer;
import org.mozilla.javascript.EvaluatorException;

public class CompressorServlet extends HttpServlet {

    Configuration config;

    class Response implements JSONString {
        private LinkedList<String> errors = new LinkedList<String>();
        private LinkedList<String> warnings = new LinkedList<String>();
        private ByteArrayOutputStream result = new ByteArrayOutputStream();
        private String charset = "UTF-8";

        public Response (Configuration config) {
            this.charset = config.getCharset();
        }

        public LinkedList<String> getErrors() {
            return errors;
        }

        public void setErrors(LinkedList<String> errors) {
            this.errors = errors;
        }

        public LinkedList<String> getWarnings() {
            return warnings;
        }

        public void setWarnings(LinkedList<String> warnings) {
            this.warnings = warnings;
        }

        public ByteArrayOutputStream getResult() {
            return result;
        }

        public void setResult(ByteArrayOutputStream result) {
            this.result = result;
        }

        public String toJSONString() {
            try {
                JSONArray warnings = new JSONArray(getWarnings());
                JSONArray errors = new JSONArray(getErrors());
                String result = getResult().toString(this.charset);
                return new JSONStringer()
                    .object().key("result").value(result)
                             .key("warnings").value(warnings)
                             .key("errors").value(errors)
                    .endObject().toString();
            } catch (JSONException ex) {
                return "JSON Failure: " + ex.getMessage();
            } catch (UnsupportedEncodingException ex) {
                return "JSON Encoding Failure: " + ex.getMessage();
            }
        }
    }

    public CompressorServlet(Configuration config) {
        config.setOutputRaw("json");
        this.config = config;
        // System.err.println("Jetty Charset: " + config.getCharset());
    }

    protected void doPut (HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {

        // Inherit configuration defaults from the command line.
        // Make a clone for this request.
        Configuration config = this.config.clone();

        String charset;
        String requestCharset = request.getCharacterEncoding();

        if (requestCharset == null) { // none specified
            charset = config.getCharset();
            // Before reading parameters or using getReader,
            // we must set the encoding.
            request.setCharacterEncoding(charset);
        } else { // use the provided encoding
            charset = requestCharset;
            config.setCharset(charset);
        }

        try {
            config = parseOptions(config, request); // get desired response format first
        } catch (ConfigurationException ex) {
            abort("Bad request", ex, HttpServletResponse.SC_BAD_REQUEST, config, response);
            return;
        }

        // Theory of operation:
        // InputStream to String
        // Decode
        // String to InputStream

        BufferedReader br = request.getReader();
        StringBuilder sb = new StringBuilder();
        String tmp = br.readLine();
        while (tmp != null) {
            sb.append(tmp);
            sb.append("\n");
            tmp = br.readLine();
        }
        String incoming = sb.toString();

        // System.err.print(incoming.toCharArray());

        Reader in = new InputStreamReader(new ByteArrayInputStream(incoming.getBytes(charset)), charset);

        Response compressorResponse;

        try {
            compressorResponse = compress(in, config);
        } catch (EvaluatorException ex) {
            // Your fault.
            abort("Syntax error", ex, HttpServletResponse.SC_BAD_REQUEST, config, response);
            return;
        } catch (IOException ex) {
            // My fault.
            abort("Compressor failed", ex, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, config, response);
            return;
        }

        respond(HttpServletResponse.SC_OK, compressorResponse, config, response);
    }

    private void respond (int httpCode, Response response, Configuration config, HttpServletResponse r) {
        try {

            String outputFormat = config.getOutput();

            boolean json = false;
            if (outputFormat.equals("json")) json = true;

            String str;
            String contentType;
            if (json) {
                contentType = "application/json";
                str = response.toJSONString();
            } else {
                if (httpCode != HttpServletResponse.SC_OK) {
                    contentType = "text/plain";
                    str = "Error: " + response.getErrors().getFirst();
                } else {
                    if (config.isCss()) {
                        contentType = "text/css";
                    } else {
                        contentType = "text/javascript";
                    }
                    byte[] resultBytes = response.getResult().toByteArray();
                    str = new String(resultBytes);
                }
            }

            r.setStatus(httpCode);
            r.setContentType(contentType);
            r.setCharacterEncoding(config.getCharset());
            PrintWriter body = r.getWriter();
            body.write(str);

        } catch (Exception ex) {
            // We can't really recover.
            System.err.println("Fatal error in HTTP server while responding to the request.");
            ex.printStackTrace();
        }
    }

    private void abort (String message, Exception ex, int httpCode, Configuration config, HttpServletResponse r)
            throws IOException {
        String error = message + ": " + ex.getMessage();
        // System.err.println(error);

        Response response = new Response(config);
        LinkedList<String> errors = new LinkedList<String>();
        errors.add(error);
        response.setErrors(errors);

        respond(httpCode, response, config, r);
    }

    private Configuration parseOptions (Configuration config, HttpServletRequest r)
            throws ConfigurationException, IOException {
        Map<String, String[]> query = (Map<String, String[]>) r.getParameterMap();

        for (String key : query.keySet()) {
            String value = query.get(key)[0];
            System.err.println(value);
            key = key.toLowerCase();
            value = value.toLowerCase();
            // System.err.println("parseOptions: " + key + " = " + value);
            if (key.equals("charset")) {
                config.setCharset(value);
            } else if (key.equals("output")) {
                config.setOutput(value);
            } else if (key.equals("type")) {
                config.setInputType(value);
            } else if (key.equals("lineBreak")) {
                config.setLineBreak(value);
            } else if (key.equals("semicolons")) {
                config.setPreserveSemicolons(value.equals(""));
            } else if (key.equals("munge")) {
                config.setMunge(value.equals("1") || value.equals("true"));
            } else if (key.equals("optimize")) {
                config.setOptimize(value.equals("1") || value.equals("true"));
            }
        }

        return config;
    }

    private Response compress (Reader in, Configuration config) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        Writer streamWriter = new OutputStreamWriter(result, config.getCharset());

        Response response = new Response(config);

        if (config.isCss()) {

            CssCompressor compressor = new CssCompressor(in);
            compressor.compress(streamWriter, config.getLineBreak());

        } else { // config.isJavascript() may also be unset. assume JS anyway.

            CompressorErrorReporter reporter = new CompressorErrorReporter();
            JavaScriptCompressor compressor = new JavaScriptCompressor(in, reporter);
            compressor.compress(streamWriter, config);
            response.setErrors(reporter.getErrors());
            response.setWarnings(reporter.getWarnings());

        }

        streamWriter.close();
        response.setResult(result);

        return response;
    }

}