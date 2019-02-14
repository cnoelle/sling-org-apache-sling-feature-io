/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.feature.io.json;

import org.apache.felix.configurator.impl.json.JSMin;
import org.apache.felix.configurator.impl.json.JSONUtil;
import org.apache.felix.configurator.impl.json.TypeConverter;
import org.apache.felix.configurator.impl.model.Config;
import org.apache.felix.utils.resource.CapabilityImpl;
import org.apache.felix.utils.resource.RequirementImpl;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Bundles;
import org.apache.sling.feature.Configuration;
import org.apache.sling.feature.Configurations;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Extensions;
import org.apache.sling.feature.Prototype;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonStructure;
import javax.json.JsonWriter;

/**
 * Common methods for JSON reading.
 */
abstract class JSONReaderBase {

    /** The optional location. */
    protected final String location;

    /** Exception prefix containing the location (if set) */
    protected final String exceptionPrefix;

    /**
     * Private constructor
     * @param location Optional location
     */
    JSONReaderBase(final String location) {
        this.location = location;
        if ( location == null ) {
            exceptionPrefix = "";
        } else {
            exceptionPrefix = location + " : ";
        }
    }

    protected String minify(final Reader reader) throws IOException {
       // minify JSON (remove comments)
        final String contents;
        try ( final Writer out = new StringWriter()) {
            final JSMin min = new JSMin(reader, out);
            min.jsmin();
            contents = out.toString();
        }
        return contents;
    }

    /**
     * Get the JSON object as a map, removing all comments that start with a '#' character
     * @param json The JSON object to process
     * @return A map representing the JSON object.
     */
    protected Map<String, Object> getJsonMap(JsonObject json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) JSONUtil.getValue(json);

        removeComments(m);
        return m;
    }

    private void removeComments(Map<String, Object> m) {
        for(Iterator<Map.Entry<String, Object>> it = m.entrySet().iterator(); it.hasNext(); ) {
            Entry<String, ?> entry = it.next();
            if (entry.getKey().startsWith("#")) {
                it.remove();
            } else if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> embedded = (Map<String, Object>) entry.getValue();
                removeComments(embedded);
            } else if (entry.getValue() instanceof Collection) {
                Collection<?> embedded = (Collection<?>) entry.getValue();
                removeComments(embedded);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void removeComments(Collection<?> embedded) {
        for (Object el : embedded) {
            if (el instanceof Collection) {
                removeComments((Collection<?>) el);
            } else if (el instanceof Map) {
                removeComments((Map<String, Object>) el);
            }
        }
    }

    protected String getProperty(final Map<String, Object> map, final String key) throws IOException {
        final Object val = map.get(key);
        if ( val != null ) {
            checkType(key, val, String.class);
            return val.toString();
        }
        return null;
    }

    /**
     * Read the variables section
     * @param map The map describing the feature or application
     * @param kvMap The variables will be written to this Key Value Map
     * @return The same variables as a normal map
     * @throws IOException If the json is invalid.
     */
    protected Map<String, String> readVariables(Map<String, Object> map, Map<String,String> kvMap) throws IOException {
        HashMap<String, String> variables = new HashMap<>();

        if (map.containsKey(JSONConstants.FEATURE_VARIABLES)) {
            final Object variablesObj = map.get(JSONConstants.FEATURE_VARIABLES);
            checkType(JSONConstants.FEATURE_VARIABLES, variablesObj, Map.class);

            @SuppressWarnings("unchecked")
            final Map<String, Object> vars = (Map<String, Object>) variablesObj;
            for (final Map.Entry<String, Object> entry : vars.entrySet()) {
                Object val = entry.getValue();
                checkType("variable value", val, String.class, Boolean.class, Number.class, null);

                String key = entry.getKey();
                if (kvMap.get(key) != null) {
                    throw new IOException(this.exceptionPrefix + "Duplicate variable " + key);
                }

                String value = val == null ? null : val.toString();

                kvMap.put(key, value);
                variables.put(key, value);
            }
        }
        return variables;
    }


    /**
     * Read the bundles / start levels section
     * @param map The map describing the feature
     * @param container The bundles container
     * @param configContainer The configurations container
     * @throws IOException If the json is invalid.
     */
    protected void readBundles(
            final Map<String, Object> map,
            final Bundles container,
            final Configurations configContainer,
            final Map<String, String> variables) throws IOException {
        if ( map.containsKey(JSONConstants.FEATURE_BUNDLES)) {
            final Object bundlesObj = map.get(JSONConstants.FEATURE_BUNDLES);
            checkType(JSONConstants.FEATURE_BUNDLES, bundlesObj, List.class);

            final List<Artifact> list = new ArrayList<>();
            readArtifacts(JSONConstants.FEATURE_BUNDLES, "bundle", list, bundlesObj, configContainer, variables);

            for(final Artifact a : list) {
                if ( container.containsExact(a.getId())) {
                    throw new IOException(exceptionPrefix + "Duplicate identical bundle " + a.getId().toMvnId());
                }
                try {
                    // check start order
                    a.getStartOrder();
                } catch ( final IllegalArgumentException nfe) {
                    throw new IOException(exceptionPrefix + "Illegal start order '" + a.getMetadata().get(Artifact.KEY_START_ORDER) + "'");
                }
                container.add(a);
            }
        }
    }

    protected void readArtifacts(final String section,
            final String artifactType,
            final List<Artifact> artifacts,
            final Object listObj,
            final Configurations container,
            final Map<String, String> variables)
    throws IOException {
        checkType(section, listObj, List.class);
        @SuppressWarnings("unchecked")
        final List<Object> list = (List<Object>) listObj;
        for(final Object entry : list) {
            final Artifact artifact;
            checkType(artifactType, entry, Map.class, String.class);
            if ( entry instanceof String ) {
                // skip comments
                if ( entry.toString().startsWith("#") ) {
                    continue;
                }
                String entry2 = replace((String) entry, variables); // TODO replace vars
                artifact = new Artifact(ArtifactId.parse(entry2));
            } else {
                @SuppressWarnings("unchecked")
                final Map<String, Object> bundleObj = (Map<String, Object>) entry;
                if ( !bundleObj.containsKey(JSONConstants.ARTIFACT_ID) ) {
                    throw new IOException(exceptionPrefix + " " + artifactType + " is missing required artifact id");
                }
                checkType(artifactType + " " + JSONConstants.ARTIFACT_ID, bundleObj.get(JSONConstants.ARTIFACT_ID), String.class);
                final String artId = replace(bundleObj.get(JSONConstants.ARTIFACT_ID).toString(), variables); // TODO replace variables
                final ArtifactId id = ArtifactId.parse(artId);

                artifact = new Artifact(id);
                for(final Map.Entry<String, Object> metadataEntry : bundleObj.entrySet()) {
                    final String key = metadataEntry.getKey();
                    if ( JSONConstants.ARTIFACT_KNOWN_PROPERTIES.contains(key) ) {
                        continue;
                    }
                    checkType(artifactType + " metadata " + key, metadataEntry.getValue(), String.class, Number.class, Boolean.class);
                    artifact.getMetadata().put(key, metadataEntry.getValue().toString());
                }
                if ( bundleObj.containsKey(JSONConstants.FEATURE_CONFIGURATIONS) ) {
                    checkType(artifactType + " configurations", bundleObj.get(JSONConstants.FEATURE_CONFIGURATIONS), Map.class);
                    addConfigurations(bundleObj, artifact, container);
                }
            }
            artifacts.add(artifact);
        }
    }
    
    private static String replace(String in, final Map<String, String> variables) {
        for (Map.Entry<String, String> keyValue : variables.entrySet()) {
        	in = in.replace("${" + keyValue.getKey() + "}", keyValue.getValue());
        }
        return in;
    }

    protected void addConfigurations(final Map<String, Object> map,
            final Artifact artifact,
            final Configurations container) throws IOException {
        final JSONUtil.Report report = new JSONUtil.Report();
        @SuppressWarnings("unchecked")
        final List<Config> configs = JSONUtil.readConfigurationsJSON(new TypeConverter(null),
                0, "", (Map<String, ?>)map.get(JSONConstants.FEATURE_CONFIGURATIONS), report);
        if ( !report.errors.isEmpty() || !report.warnings.isEmpty() ) {
            final StringBuilder builder = new StringBuilder(exceptionPrefix);
            builder.append("Errors in configurations:");
            for(final String w : report.warnings) {
                builder.append("\n");
                builder.append(w);
            }
            for(final String e : report.errors) {
                builder.append("\n");
                builder.append(e);
            }
            throw new IOException(builder.toString());
        }

        for(final Config c : configs) {
            final Configuration config = new Configuration(c.getPid());

            final Enumeration<String> keyEnum = c.getProperties().keys();
            while ( keyEnum.hasMoreElements() ) {
                final String key = keyEnum.nextElement();
                final Object val = c.getProperties().get(key);
                config.getProperties().put(key, val);
            }
            if ( config.getProperties().get(Configuration.PROP_ARTIFACT_ID) != null ) {
                throw new IOException(exceptionPrefix + "Configuration must not define property " + Configuration.PROP_ARTIFACT_ID);
            }
            if ( artifact != null ) {
                config.getProperties().put(Configuration.PROP_ARTIFACT_ID, artifact.getId().toMvnId());
            }
            for(final Configuration current : container) {
                if ( current.equals(config) ) {
                    throw new IOException(exceptionPrefix + "Duplicate configuration " + config);
                }
            }
            container.add(config);
        }
    }


    protected void readConfigurations(final Map<String, Object> map,
            final Configurations container) throws IOException {
        if ( map.containsKey(JSONConstants.FEATURE_CONFIGURATIONS) ) {
            checkType(JSONConstants.FEATURE_CONFIGURATIONS, map.get(JSONConstants.FEATURE_CONFIGURATIONS), Map.class);
            addConfigurations(map, null, container);
        }
    }

    protected void readFrameworkProperties(final Map<String, Object> map,
            final Map<String,String> container) throws IOException {
        if ( map.containsKey(JSONConstants.FEATURE_FRAMEWORK_PROPERTIES) ) {
            final Object propsObj= map.get(JSONConstants.FEATURE_FRAMEWORK_PROPERTIES);
            checkType(JSONConstants.FEATURE_FRAMEWORK_PROPERTIES, propsObj, Map.class);

            @SuppressWarnings("unchecked")
            final Map<String, Object> props = (Map<String, Object>) propsObj;
            for(final Map.Entry<String, Object> entry : props.entrySet()) {
                checkType("framework property value", entry.getValue(), String.class, Boolean.class, Number.class);
                if ( container.get(entry.getKey()) != null ) {
                    throw new IOException(this.exceptionPrefix + "Duplicate framework property " + entry.getKey());
                }
                container.put(entry.getKey(), entry.getValue().toString());
            }

        }
    }

    protected void readExtensions(final Map<String, Object> map,
            final List<String> keywords,
            final Extensions container,
            final Configurations configContainer) throws IOException {
        final Set<String> keySet = new HashSet<>(map.keySet());
        keySet.removeAll(keywords);
        // the remaining keys are considered extensions!
        for(final String key : keySet) {
            final int pos = key.indexOf(':');
            final String postfix = pos == -1 ? null : key.substring(pos + 1);
            final int sep = (postfix == null ? key.indexOf('|') : postfix.indexOf('|'));
            final String name;
            final String type;
            final String optional;
            if ( pos == -1 ) {
                type = ExtensionType.ARTIFACTS.name();
                if ( sep == -1 ) {
                    name = key;
                    optional = Boolean.FALSE.toString();
                } else {
                    name = key.substring(0, sep);
                    optional = key.substring(sep + 1);
                }
            } else {
                name = key.substring(0, pos);
                if ( sep == -1 ) {
                    type = postfix;
                    optional = Boolean.FALSE.toString();
                } else {
                    type = postfix.substring(0, sep);
                    optional = postfix.substring(sep + 1);
                }
            }
            if ( JSONConstants.FEATURE_KNOWN_PROPERTIES.contains(name) ) {
                throw new IOException(this.exceptionPrefix + "Extension is using reserved name : " + name);
            }
            if ( container.getByName(name) != null ) {
                throw new IOException(exceptionPrefix + "Duplicate extension with name " + name);
            }

            final ExtensionType extType = ExtensionType.valueOf(type);
            final boolean opt = Boolean.valueOf(optional).booleanValue();

            final Extension ext = new Extension(extType, name, opt);
            final Object value = map.get(key);
            switch ( extType ) {
                case ARTIFACTS : final List<Artifact> list = new ArrayList<>();
                                 readArtifacts("Extension " + name, "artifact", list, value, configContainer, Collections.emptyMap());
                                 for(final Artifact a : list) {
                                     if ( ext.getArtifacts().contains(a) ) {
                                         throw new IOException(exceptionPrefix + "Duplicate artifact in extension " + name + " : " + a.getId().toMvnId());
                                     }
                                     ext.getArtifacts().add(a);
                                 }
                                 break;
                case JSON : checkType("JSON Extension " + name, value, Map.class, List.class);
                            final JsonStructure struct = build(value);
                            try ( final StringWriter w = new StringWriter()) {
                                final JsonWriter jw = Json.createWriter(w);
                                jw.write(struct);
                                w.flush();
                                ext.setJSON(w.toString());
                            }
                            break;
                case TEXT : checkType("Text Extension " + name, value, String.class, List.class);
                            if ( value instanceof String ) {
                                // string
                                ext.setText(value.toString());
                            } else {
                                // list (array of strings)
                                @SuppressWarnings("unchecked")
                                final List<Object> l = (List<Object>)value;
                                final StringBuilder sb = new StringBuilder();
                                for(final Object o : l) {
                                    checkType("Text Extension " + name + ", value " + o, o, String.class);
                                    sb.append(o.toString());
                                    sb.append('\n');
                                }
                                ext.setText(sb.toString());
                            }
                            break;
            }

            container.add(ext);
        }
    }

    private JsonStructure build(final Object value) {
        if ( value instanceof List ) {
            @SuppressWarnings("unchecked")
            final List<Object> list = (List<Object>)value;
            final JsonArrayBuilder builder = Json.createArrayBuilder();
            for(final Object obj : list) {
                if ( obj instanceof String ) {
                    builder.add(obj.toString());
                } else if ( obj instanceof Long ) {
                    builder.add((Long)obj);
                } else if ( obj instanceof Double ) {
                    builder.add((Double)obj);
                } else if (obj instanceof Boolean ) {
                    builder.add((Boolean)obj);
                } else if ( obj instanceof Map ) {
                    builder.add(build(obj));
                } else if ( obj instanceof List ) {
                    builder.add(build(obj));
                }

            }
            return builder.build();
        } else if ( value instanceof Map ) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>)value;
            final JsonObjectBuilder builder = Json.createObjectBuilder();
            for(final Map.Entry<String, Object> entry : map.entrySet()) {
                if ( entry.getValue() instanceof String ) {
                    builder.add(entry.getKey(), entry.getValue().toString());
                } else if ( entry.getValue() instanceof Long ) {
                    builder.add(entry.getKey(), (Long)entry.getValue());
                } else if ( entry.getValue() instanceof Double ) {
                    builder.add(entry.getKey(), (Double)entry.getValue());
                } else if ( entry.getValue() instanceof Boolean ) {
                    builder.add(entry.getKey(), (Boolean)entry.getValue());
                } else if ( entry.getValue() instanceof Map ) {
                    builder.add(entry.getKey(), build(entry.getValue()));
                } else if ( entry.getValue() instanceof List ) {
                    builder.add(entry.getKey(), build(entry.getValue()));
                }
            }
            return builder.build();
        }
        return null;
    }

    /**
     * Check if the value is one of the provided types
     * @param key A key for the error message
     * @param val The value to check
     * @param types The allowed types, can also include {@code null} if null is an allowed value.
     * @throws IOException If the val is not of the specified types
     */
    protected void checkType(final String key, final Object val, Class<?>...types) throws IOException {
        boolean valid = false;
        for(final Class<?> c : types) {
            if (c == null) {
                if ( val == null) {
                    valid = true;
                    break;
                }
            } else if ( c.isInstance(val) ) {
                valid = true;
                break;
            }
        }
        if ( !valid ) {
            throw new IOException(this.exceptionPrefix + "Key " + key + " is not one of the allowed types " + Arrays.toString(types) + " : " + val.getClass());
        }
    }

    protected Prototype readPrototype(final Map<String, Object> map) throws IOException {
        if ( map.containsKey(JSONConstants.FEATURE_PROTOTYPE)) {
            final Object prototypeObj = map.get(JSONConstants.FEATURE_PROTOTYPE);
            checkType(JSONConstants.FEATURE_PROTOTYPE, prototypeObj, Map.class, String.class);

            final Prototype prototype;
            if ( prototypeObj instanceof String ) {
                final ArtifactId id = ArtifactId.parse(prototypeObj.toString());
                prototype = new Prototype(id);
            } else {
                @SuppressWarnings("unchecked")
                final Map<String, Object> obj = (Map<String, Object>) prototypeObj;
                if ( !obj.containsKey(JSONConstants.ARTIFACT_ID) && !obj.containsKey(JSONConstants.ARTIFACT_LOCATION)) {
                    throw new IOException(exceptionPrefix + " prototype is missing required artifact id or url");
                }
                final boolean isUrl = obj.containsKey(JSONConstants.ARTIFACT_LOCATION);
                if (isUrl)
            		checkType("Prototype " + JSONConstants.ARTIFACT_LOCATION, obj.get(JSONConstants.ARTIFACT_LOCATION), String.class);
                else 
                	checkType("Prototype " + JSONConstants.ARTIFACT_ID, obj.get(JSONConstants.ARTIFACT_ID), String.class);
                
                // if prototype contains location -> read from location
                if (isUrl) {
                	final String prototypeLocation = obj.get(JSONConstants.ARTIFACT_LOCATION).toString();
                	try {
               			 prototype = new Prototype(new URL(prototypeLocation));
                	} catch (MalformedURLException e) {
                		throw new IOException("Not a valid prototype location: " + prototypeLocation, e);
                	}
                } else {
	                final ArtifactId id = ArtifactId.parse(obj.get(JSONConstants.ARTIFACT_ID).toString());
	                prototype = new Prototype(id);
                }

                if ( obj.containsKey(JSONConstants.PROTOTYPE_REMOVALS) ) {
                    checkType("Prototype removals", obj.get(JSONConstants.PROTOTYPE_REMOVALS), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> removalObj = (Map<String, Object>) obj.get(JSONConstants.PROTOTYPE_REMOVALS);
                    if ( removalObj.containsKey(JSONConstants.FEATURE_BUNDLES) ) {
                        checkType("Prototype removal bundles", removalObj.get(JSONConstants.FEATURE_BUNDLES), List.class);
                        @SuppressWarnings("unchecked")
                        final List<Object> list = (List<Object>)removalObj.get(JSONConstants.FEATURE_BUNDLES);
                        for(final Object val : list) {
                            checkType("Prototype removal bundles", val, String.class);
                            if ( val.toString().startsWith("#")) {
                                continue;
                            }
                            prototype.getBundleRemovals().add(ArtifactId.parse(val.toString()));
                        }
                    }
                    if ( removalObj.containsKey(JSONConstants.FEATURE_CONFIGURATIONS) ) {
                        checkType("Prototype removal configuration", removalObj.get(JSONConstants.FEATURE_CONFIGURATIONS), List.class);
                        @SuppressWarnings("unchecked")
                        final List<Object> list = (List<Object>)removalObj.get(JSONConstants.FEATURE_CONFIGURATIONS);
                        for(final Object val : list) {
                            checkType("Prototype removal configuration", val, String.class);
                            prototype.getConfigurationRemovals().add(val.toString());
                        }
                    }
                    if ( removalObj.containsKey(JSONConstants.FEATURE_FRAMEWORK_PROPERTIES) ) {
                        checkType("Prototype removal framework properties", removalObj.get(JSONConstants.FEATURE_FRAMEWORK_PROPERTIES), List.class);
                        @SuppressWarnings("unchecked")
                        final List<Object> list = (List<Object>)removalObj.get(JSONConstants.FEATURE_FRAMEWORK_PROPERTIES);
                        for(final Object val : list) {
                            checkType("Prototype removal framework properties", val, String.class);
                            prototype.getFrameworkPropertiesRemovals().add(val.toString());
                        }
                    }
                    if ( removalObj.containsKey(JSONConstants.PROTOTYPE_EXTENSION_REMOVALS) ) {
                        checkType("Prototype removal extensions", removalObj.get(JSONConstants.PROTOTYPE_EXTENSION_REMOVALS), List.class);
                        @SuppressWarnings("unchecked")
                        final List<Object> list = (List<Object>)removalObj.get(JSONConstants.PROTOTYPE_EXTENSION_REMOVALS);
                        for(final Object val : list) {
                            checkType("Prototype removal extension", val, String.class, Map.class);
                            if ( val instanceof String ) {
                                if ( val.toString().startsWith("#")) {
                                    continue;
                                }
                                prototype.getExtensionRemovals().add(val.toString());
                            } else {
                                @SuppressWarnings("unchecked")
                                final Map<String, Object> removalMap = (Map<String, Object>)val;
                                final Object nameObj = removalMap.get("name");
                                checkType("Prototype removal extension", nameObj, String.class);
                                if ( removalMap.containsKey("artifacts") ) {
                                    checkType("Prototype removal extension artifacts", removalMap.get("artifacts"), List.class);
                                    @SuppressWarnings("unchecked")
                                    final List<Object> artifactList = (List<Object>)removalMap.get("artifacts");
                                    final List<ArtifactId> ids = new ArrayList<>();
                                    for(final Object aid : artifactList) {
                                        checkType("Prototype removal extension artifact", aid, String.class);
                                        ids.add(ArtifactId.parse(aid.toString()));
                                    }
                                    prototype.getArtifactExtensionRemovals().put(nameObj.toString(), ids);
                                } else {
                                    prototype.getExtensionRemovals().add(nameObj.toString());
                                }
                            }
                        }
                    }

                }
            }
            return prototype;
        }
        return null;
    }

    protected void readRequirements(Map<String, Object> map, final List<Requirement> container) throws IOException {
        if ( map.containsKey(JSONConstants.FEATURE_REQUIREMENTS)) {
            final Object reqObj = map.get(JSONConstants.FEATURE_REQUIREMENTS);
            checkType(JSONConstants.FEATURE_REQUIREMENTS, reqObj, List.class);

            @SuppressWarnings("unchecked")
            final List<Object> requirements = (List<Object>)reqObj;
            for(final Object req : requirements) {
                checkType("Requirement", req, Map.class);
                @SuppressWarnings("unchecked")
                final Map<String, Object> obj = (Map<String, Object>) req;

                if ( !obj.containsKey(JSONConstants.REQCAP_NAMESPACE) ) {
                    throw new IOException(this.exceptionPrefix + "Namespace is missing for requirement");
                }
                checkType("Requirement namespace", obj.get(JSONConstants.REQCAP_NAMESPACE), String.class);

                Map<String, Object> attrMap = new HashMap<>();
                if ( obj.containsKey(JSONConstants.REQCAP_ATTRIBUTES) ) {
                    checkType("Requirement attributes", obj.get(JSONConstants.REQCAP_ATTRIBUTES), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> attrs = (Map<String, Object>)obj.get(JSONConstants.REQCAP_ATTRIBUTES);
                    attrs.forEach(rethrowBiConsumer((key, value) -> ManifestUtils.unmarshalAttribute(key, value, attrMap::put)));
                }

                Map<String, String> dirMap = new HashMap<>();
                if ( obj.containsKey(JSONConstants.REQCAP_DIRECTIVES) ) {
                    checkType("Requirement directives", obj.get(JSONConstants.REQCAP_DIRECTIVES), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> dirs = (Map<String, Object>)obj.get(JSONConstants.REQCAP_DIRECTIVES);
                    dirs.forEach(rethrowBiConsumer((key, value) -> ManifestUtils.unmarshalDirective(key, value, dirMap::put)));
                }

                final Requirement r = new RequirementImpl(null, obj.get(JSONConstants.REQCAP_NAMESPACE).toString(), dirMap, attrMap);
                container.add(r);
            }
        }
    }

    protected void readCapabilities(Map<String, Object> map, final List<Capability> container) throws IOException {
        if ( map.containsKey(JSONConstants.FEATURE_CAPABILITIES)) {
            final Object capObj = map.get(JSONConstants.FEATURE_CAPABILITIES);
            checkType(JSONConstants.FEATURE_CAPABILITIES, capObj, List.class);

            @SuppressWarnings("unchecked")
            final List<Object> capabilities = (List<Object>)capObj;
            for(final Object cap : capabilities) {
                checkType("Capability", cap, Map.class);
                @SuppressWarnings("unchecked")
                final Map<String, Object> obj = (Map<String, Object>) cap;

                if ( !obj.containsKey(JSONConstants.REQCAP_NAMESPACE) ) {
                    throw new IOException(this.exceptionPrefix + "Namespace is missing for capability");
                }
                checkType("Capability namespace", obj.get(JSONConstants.REQCAP_NAMESPACE), String.class);

                Map<String, Object> attrMap = new HashMap<>();
                if ( obj.containsKey(JSONConstants.REQCAP_ATTRIBUTES) ) {
                    checkType("Capability attributes", obj.get(JSONConstants.REQCAP_ATTRIBUTES), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> attrs = (Map<String, Object>)obj.get(JSONConstants.REQCAP_ATTRIBUTES);
                    attrs.forEach(rethrowBiConsumer((key, value) -> ManifestUtils.unmarshalAttribute(key, value, attrMap::put)));
                }

                Map<String, String> dirMap = new HashMap<>();
                if ( obj.containsKey(JSONConstants.REQCAP_DIRECTIVES) ) {
                    checkType("Capability directives", obj.get(JSONConstants.REQCAP_DIRECTIVES), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> dirs = (Map<String, Object>) obj.get(JSONConstants.REQCAP_DIRECTIVES);
                    dirs.forEach(rethrowBiConsumer((key, value) -> ManifestUtils.unmarshalDirective(key, value, dirMap::put)));
                }

                final Capability c = new CapabilityImpl(null, obj.get(JSONConstants.REQCAP_NAMESPACE).toString(), dirMap, attrMap);
                container.add(c);
            }
        }
    }

    @FunctionalInterface
    private interface BiConsumer_WithExceptions<T, V, E extends Exception> {
        void accept(T t, V u) throws E;
    }

    private static <T, V, E extends Exception> BiConsumer<T, V> rethrowBiConsumer(BiConsumer_WithExceptions<T, V, E> biConsumer) {
        return (t, u) -> {
            try {
                biConsumer.accept(t, u);
            } catch (Exception exception) {
                throwAsUnchecked(exception);
            }
        };
    }

    @SuppressWarnings ("unchecked")
    private static <E extends Throwable> void throwAsUnchecked(Exception exception) throws E {
        throw (E) exception;
    }
}
