/*  Copyright (C) 2009 Mobile Sorcery AB

    This program is free software; you can redistribute it and/or modify it
    under the terms of the Eclipse Public License v1.0.

    This program is distributed in the hope that it will be useful, but WITHOUT
    ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
    FITNESS FOR A PARTICULAR PURPOSE. See the Eclipse Public License v1.0 for
    more details.

    You should have received a copy of the Eclipse Public License v1.0 along
    with this program. It is also available at http://www.eclipse.org/legal/epl-v10.html
*/
package com.mobilesorcery.sdk.core.templates;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// TODO: How [if] integrate with org.eclipse.ui.editors.templates??
public class Template implements ITemplate {

    private String desc;
    private String id;
    private URL template;
    private String name;
    private String pathToResolvedTemplate;
    private Map<String, String> projectSettings;

    /*
     * Refactoring remains: still used for one-file templates (=resource files)
     */
    public Template(URL template) {
        this("", "", "", template, "", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
    
    public Template(String id, String name, String desc, URL template, String pathToResolvedTemplate, Map<String, String> projectSettings) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.template = template;
        this.pathToResolvedTemplate = pathToResolvedTemplate;
        this.projectSettings = projectSettings;
    }

    public String getDescription() {
        return desc;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPathToResolvedTemplate() {
        return pathToResolvedTemplate;
    }
    
    public Map<String, String> getSettings() {
        return projectSettings;
    }

    public void setTemplateURI(URL template) {
        this.template = template;
    }

    public String resolve(Map<String, String> map) throws IOException {
        if (map == null) {
            // No NPE's, please.
            map = new HashMap<String, String>();
        }
        
        if (template == null) {
            throw new IOException("Template does not exist"); //$NON-NLS-1$
        }

        Reader stream = new InputStreamReader(new BufferedInputStream(this.template.openStream()));
        StringBuffer buffer = new StringBuffer();
        try {
            for (int read = stream.read(); read != -1; read = stream.read()) {
                buffer.append((char) read);
            }
        } finally {
            stream.close();
        }

        String template = buffer.toString();
        
        return verySimplePreprocessing(replace(template, map), map.keySet());
    }    
    
    public static String replace(String input, Map<String, String> map) {
        return innerReplace(input, map, 0).toString();
    }
    
    private static StringBuffer innerReplace(String input, Map<String, String> map, int depth) {
        if (depth > 12) {
            throw new IllegalArgumentException("Cyclic parameters"); //$NON-NLS-1$
        }
        
        StringBuffer result = new StringBuffer();
        char[] chars = input.toCharArray();
        boolean inParam = false;
        int paramStart = 0;
        
        for (int i = 0; i < chars.length; i++) {
            if ('%' == chars[i]) {
                if (!inParam) {
                    paramStart = i;
                } else {
                    String paramName = input.substring(paramStart + 1, i);
                    if (paramName.length() > 0 && paramName.charAt(0) == '#') {
                        // TODO.
                    } else {
                        if (paramName.length() == 0) { // Escape pattern %% => %
                            result.append("%"); //$NON-NLS-1$
                        } else {
                            String paramValue = map.get(paramName);
                            if (paramValue != null) {
                                result.append(innerReplace(paramValue, map, depth + 1));
                            } else {
                                // Just leave as-is
                                result.append('%');
                                result.append(paramName);
                                result.append('%');
                            }
                        }
                    }
                }
                
                inParam = !inParam;
            } else if (!inParam) {
                result.append(chars[i]);
            }
        }
        
        return result;
    }

    public static String verySimplePreprocessing(String input, Set<String> flags) {
        String[] lines = input.split("\n"); //$NON-NLS-1$
        StringBuffer result = new StringBuffer();
        boolean doOutput = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("#ifdef")) { //$NON-NLS-1$
                String[] ifdefLine = line.split("\\s+", 2); //$NON-NLS-1$
                if (ifdefLine.length == 2) {
                    doOutput = flags.contains(ifdefLine[1].trim());
                }
            } else if (line.trim().startsWith("#endif")) { //$NON-NLS-1$
                doOutput = true;
            } else if (doOutput) {
                result.append(line);
                result.append('\n');
            }
        }
        
        return result.toString();
    }
        
    public static void main(String[] args) {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("A", "%B%FG"); //$NON-NLS-1$ //$NON-NLS-2$
        map.put("B", "oo"); //$NON-NLS-1$ //$NON-NLS-2$
        System.err.println(replace("%urg%%B%%%", map)); //$NON-NLS-1$
        
        System.err.println(verySimplePreprocessing("dfd\n#ifdef   G\nblujf\n#endif\nfdsf", map.keySet())); //$NON-NLS-1$
    }
}
