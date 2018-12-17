/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.elasticsearch.plugin.kibana;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.search.SearchHit;

import com.github.zafarkhaja.semver.Version;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import io.fabric8.elasticsearch.plugin.ConfigurationSettings;
import io.fabric8.elasticsearch.plugin.OpenshiftRequestContextFactory.OpenshiftRequestContext;
import io.fabric8.elasticsearch.plugin.PluginClient;
import io.fabric8.elasticsearch.plugin.PluginSettings;
import io.fabric8.elasticsearch.plugin.model.Project;

public class KibanaUtils {
    
    private static final Logger LOGGER = LogManager.getLogger(KibanaUtils.class);
    public static final String INDICIES_TYPE = "index-pattern";
    public static final Project ALL_ALIAS = new Project(".all", null);
    public static final Project EMPTY_PROJECT = new Project(".empty-project", null);
            
    private final PluginClient pluginClient;
    private String projectPrefix;
    private final Pattern reIndexPattern;
    private final Version defaultVersion;
    private final JsonPath defaultPath = JsonPath.compile("$.defaultIndex");

    public KibanaUtils(final PluginSettings settings, final PluginClient pluginClient) {
        this.pluginClient = pluginClient;
        this.projectPrefix = StringUtils.isNotBlank(settings.getCdmProjectPrefix()) ? settings.getCdmProjectPrefix() : "";
        this.reIndexPattern = Pattern.compile("^" + projectPrefix + "\\.(?<name>[a-zA-Z0-9-]*)\\.(?<uid>.*)\\.\\*$");
        this.defaultVersion = Version.valueOf(ConfigurationSettings.DEFAULT_KIBANA_VERSION);
    }
    
    /**
     * Determine a set of projects based on the index patterns that 
     * were generated by this plugin.  Any index patterns that are user
     * generated which do not match the format 'project.$NAME.$UID.*'
     * are ignored
     * 
     * @param context   The OpenshiftRequestContext for this request
     * @return a set of projects
     */
    public Set<Project> getProjectsFromIndexPatterns(OpenshiftRequestContext context) {
        Set<Project> patterns = new HashSet<>();
        SearchResponse response = pluginClient.search(context.getKibanaIndex(), INDICIES_TYPE);
        if (response.getHits() != null && response.getHits().getTotalHits() > 0) {
            for (SearchHit hit : response.getHits().getHits()) {
                String id = hit.getId();
                Project project = getProjectFromIndexPattern(id);

                if (!project.getName().equals(id) || project.equals(ALL_ALIAS)) {
                    patterns.add(project);
                }

                // else we found a user created index-pattern. Ignore
            }
        } else {
            LOGGER.debug("No index-mappings found in the kibana index '{}'", context.getKibanaIndex());
        }

        return patterns;
    }
    
    public Project getProjectFromIndexPattern(String index) {

        if (StringUtils.isEmpty(index)) {
            return Project.EMPTY;
        }

        Matcher matcher = reIndexPattern.matcher(index);
        if (matcher.matches()) {
            return new Project(matcher.group("name"), matcher.group("uid"));
        }
        return new Project(index, null);
    }
    
    public String formatIndexPattern(Project project) {
        String prefix = StringUtils.isNotEmpty(projectPrefix) ? projectPrefix + "." : "";
        if (project.equals(ALL_ALIAS)) {
            return ALL_ALIAS.getName();
        }
        if (project.equals(EMPTY_PROJECT)) { 
            return prefix + project.getName().substring(1) + ".*";
        }
        if (project.getUID() == null) {
            return project.getName() + (project.getName().endsWith(".*") ? "" : ".*");
        }
        String uid = project.getUID() != null ? "." + project.getUID() : "";
        return String.format("%s%s%s.*", prefix, project.getName(), uid);
    }
    
    /**
     * Gets the default index-pattern if not set or set on the wrong version of 
     * Kibana or empty if it doesnt need to be set
     * 
     * @param kibanaIndex The index to Check
     * @param defaultIfNotSet The value to use if not set
     * @return The default index-pattern
     */
    public String getDefaultIndexPattern(String kibanaIndex, String defaultIfNotSet) {
        // default if config doesnt exist or is not set
        // the value if config doesnt exist but previous does
        try {
            SearchResponse response = pluginClient.search(kibanaIndex, "config");
            final long totalHits = response.getHits().getTotalHits();
            if(totalHits == 0) {
                return defaultIfNotSet;
            } else if (totalHits == 1){
                try {
                    String value = defaultPath.read(response.getHits().getHits()[0].getSourceAsString());
                    return StringUtils.isNotEmpty(value) ? value : defaultIfNotSet;
                }catch(PathNotFoundException e) {
                    return defaultIfNotSet;
                }
            }
            Map<Version, String> patternMap = new HashMap<>();
            for (SearchHit hit : response.getHits().getHits()) {
                String source = hit.getSourceAsString();
                String defaultIndex = defaultIfNotSet;
                try {
                    defaultIndex = defaultPath.read(source);
                }catch(PathNotFoundException e) {
                    // skip
                }
                patternMap.put(Version.valueOf(hit.getId()), defaultIndex);
            }
            List<Version> versions = new ArrayList<>(patternMap.keySet());
            Collections.sort(versions);
            if(versions.contains(defaultVersion)) {
                return StringUtils.defaultIfBlank(patternMap.get(defaultVersion), "");
            } else {
                return patternMap.get(versions.get(versions.size() - 1));
            }
        }catch (IndexNotFoundException e) {
            return defaultIfNotSet;
        }
    }
}