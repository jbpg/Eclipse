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
package com.mobilesorcery.sdk.internal;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import com.mobilesorcery.sdk.core.BuildResult;
import com.mobilesorcery.sdk.core.BuildVariant;
import com.mobilesorcery.sdk.core.CoreMoSyncPlugin;
import com.mobilesorcery.sdk.core.IBuildResult;
import com.mobilesorcery.sdk.core.IBuildState;
import com.mobilesorcery.sdk.core.IBuildVariant;
import com.mobilesorcery.sdk.core.IFileTreeDiff;
import com.mobilesorcery.sdk.core.MoSyncBuilder;
import com.mobilesorcery.sdk.core.MoSyncProject;
import com.mobilesorcery.sdk.core.PropertyUtil;
import com.mobilesorcery.sdk.core.SectionedPropertiesFile;
import com.mobilesorcery.sdk.core.SectionedPropertiesFile.Section;
import com.mobilesorcery.sdk.core.SectionedPropertiesFile.Section.Entry;
import com.mobilesorcery.sdk.core.Util;
import com.mobilesorcery.sdk.internal.dependencies.DependencyManager;


public class BuildState implements IBuildState {

    public static class Diff implements IFileTreeDiff {
        private ArrayList<IPath> added = new ArrayList<IPath>();
        private ArrayList<IPath> changed = new ArrayList<IPath>();
        private ArrayList<IPath> removed = new ArrayList<IPath>();
        
        public List<IPath> getAdded() {
            return added;
        }
        
        public List<IPath> getChanged() {
            return changed;
        }
        
        public List<IPath> getRemoved() {
            return removed;
        }
        
        public String toString() {
            return "ADDED:        " + added + "\n" +
                   "CHANGED:      " + changed + "\n" +
                   "REMOVED:      " + removed;
        }

		@Override
		public boolean isEmpty() {
			return added.isEmpty() && changed.isEmpty() && removed.isEmpty();
		}
    }
    
    class FileInfoTree implements IResourceVisitor {
        HashMap<IPath, Long> timestampMap = new HashMap<IPath, Long>(); 
        
        /**
         * Computes a diff between this tree and another tree,
         * with this tree as the "to" tree, and the other tree
         * as the "from" tree. Or, informally: other + diff = this
         * @param other
         * @return
         */
        Diff computeDiff(FileInfoTree other) {
            Diff diff = new Diff();
            for (IPath path : timestampMap.keySet()) {
                Long otherTimestamp = other.timestampMap.get(path);
                if (otherTimestamp == null) {
                    diff.added.add(path);
                } else if (otherTimestamp.compareTo(timestampMap.get(path)) != 0) {
                    diff.changed.add(path);
                }
                
                if (CoreMoSyncPlugin.getDefault().isDebugging()) {
                    CoreMoSyncPlugin.trace("{0} previous timestamp: {1}, current timestamp: {2}", path, new Date(otherTimestamp), timestampMap.get(path));
                }
            }
            
            for (IPath path : other.timestampMap.keySet()) {
                if (!timestampMap.containsKey(path)) {
                    diff.removed.add(path);
                }
            }
            
            return diff;
        }
        
        public boolean visit(IResource resource) throws CoreException {
            internalUpdateResource(resource);
            return !resource.isDerived();
        }
        
        private void internalUpdateResource(IResource resource) {
            if (resource.getType() == IResource.FILE) {
                internalUpdateState(resource.getProjectRelativePath());
            }
        }

        private void internalUpdateState(IPath path) {
        	IResource projectResource = project.getWrappedProject().findMember(path);
 
        	/**
        	 * Ignore virtual folders since they do not have a timestamp and
        	 * getLocation always returns null for them.
        	 */
          	if(projectResource.isVirtual()) {
        		return;
        	}
        	
        	IPath fullpath = projectResource.getLocation();    	
        	File file = fullpath.toFile();
            long newTimestamp = file.lastModified();
            timestampMap.put(path, newTimestamp);
        }

        public void removeState(IPath removed) {
            timestampMap.remove(removed);
        }
    }
    
    private FileInfoTree tree;
    private DependencyManager<IResource> dependencies;
    
    private IBuildVariant variant;
    private MoSyncProject project;
    private File buildStateFile;
    private IBuildResult buildResult;


    private boolean valid = true;
    private boolean fullRebuildNeeded;
    private Map<String, String> properties;

    public BuildState(MoSyncProject project, IBuildVariant variant) {
        this.project = project;
        this.variant = variant;
        IPath metaDataPath = MoSyncBuilder.getMetaDataPath(project, variant);
        IPath buildStatePath = metaDataPath.append(".buildstate");
        buildStateFile = buildStatePath.toFile();
        clear();
        load();
    }
    
    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#load()
     */
    public void load() {
        valid = buildStateFile.exists();
        if (valid) {
            try {
                parseBuildStateFile(buildStateFile);
            } catch (Exception e) {
                CoreMoSyncPlugin.getDefault().log(e);
                valid = false;
                // We just consider it a 'fresh' build [state].
            }
        }
    }

    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#isValid()
     */
    public boolean isValid() {
        return valid;
    }
    
    private void parseBuildStateFile(File buildStateFile) throws IOException {
        SectionedPropertiesFile props = SectionedPropertiesFile.parse(buildStateFile);
        
        Section resultSection = props.getDefaultSection();
        parseBuildResult(resultSection);
        
        Section files = props.getFirstSection("files");
        parseFileState(files);
        
        Section dependenciesSection = props.getFirstSection("dependencies");
        parseDependencies(dependenciesSection);
        
        Section buildPropertiesSection = props.getFirstSection("build-properties");
        parseBuildProperties(buildPropertiesSection);
    }
    
    private void parseBuildProperties(Section buildPropertiesSection) {
        if (buildPropertiesSection == null) {
            return;
        }
     
        properties = buildPropertiesSection.getEntriesAsMap();
    }

    private void parseDependencies(Section dependenciesSection) {
        if (dependenciesSection == null) {
            return;
        }
        
        List<Entry> dependencyEntries = dependenciesSection.getEntries();
        for (Entry dependencyEntry : dependencyEntries) {
            IPath dependeePath = new Path(dependencyEntry.getKey());
            IPath[] dependencyPaths = PropertyUtil.toPaths(dependencyEntry.getValue());
            // Ehm... TODO: Dependencies should be on absolute paths, not resources...
            IWorkspaceRoot wr = ResourcesPlugin.getWorkspace().getRoot();
            //IFile[] dependeeFiles = wr.findFilesForLocation(dependeePath);
            IResource[] dependeeFiles = new IResource[] { wr.findMember(dependeePath) }; 
            for (int i = 0; i < dependeeFiles.length; i++) {
                for (int j = 0; j < dependencyPaths.length; j++) {
                    IResource[] dependencyFiles = new IResource[] { wr.findMember(dependencyPaths[i]) };
                    for (int k = 0; k < dependencyFiles.length; k++) {
                        if (dependeeFiles[i] != null) {
                            dependencies.addDependency(dependeeFiles[i], dependencyFiles[k]);
                        }
                    }
                }
            }
        }
    }

    private void parseFileState(Section files) {
        if (files == null) {
            return;
        }
        
        List<Entry> entries = files.getEntries();
        for (Entry entry : entries) {
            IPath path = new Path(entry.getKey());
            long timestamp = Long.parseLong(entry.getValue());
            tree.timestampMap.put(path, timestamp);
        }
    }

    private void parseBuildResult(Section resultSection) {
        if (resultSection == null) {
            return;
        }
        
        Map<String, String> resultMap = resultSection.getEntriesAsMap();
        BuildResult buildResult = new BuildResult(project.getWrappedProject());
        IBuildVariant variant = BuildVariant.parse(resultMap.get("variant"));
        buildResult.setVariant(variant);
        buildResult.setSuccess(Boolean.parseBoolean(resultMap.get("success")));
        fullRebuildNeeded = Boolean.parseBoolean(resultMap.get("rebuild-needed"));
        String timestampStr = resultMap.get("timestamp");
        if (timestampStr != null) {
            buildResult.setTimestamp(Long.parseLong(timestampStr));
        }
        String outputFileName = resultMap.get("output");
        if (outputFileName != null) {
            buildResult.setBuildResult(new File(outputFileName));
        }
        this.buildResult = buildResult;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }
    
    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#save()
     */
    public void save() {
        FileWriter buildStateWriter = null;
        try {
            buildStateFile.getParentFile().mkdirs();
            buildStateWriter = new FileWriter(buildStateFile);
            SectionedPropertiesFile props = SectionedPropertiesFile.create();
            
            Section resultSection = props.getDefaultSection();
            saveBuildResult(resultSection);
            
            Section files = props.addSection("files");
            saveFileState(files);
            
            Section deps = props.addSection("dependencies");
            saveDependencies(deps);
            
            Section buildPropertiesSection = props.addSection("build-properties");
            saveBuildProperties(buildPropertiesSection);
            
            buildStateWriter.write(props.toString());
        } catch (Exception e) {
            CoreMoSyncPlugin.getDefault().log(e);
            e.printStackTrace();
            // We silently ignore it.
        } finally {    
            Util.safeClose(buildStateWriter);    
        }
    }
    
    private void saveBuildProperties(Section buildPropertiesSection) {
        buildPropertiesSection.addEntries(properties);
    }
    
    private void saveDependencies(Section deps) {
        for (IResource dependee : dependencies.getAllDependees()) {
            if (dependee != null) {
                IPath dependeePath = dependee.getFullPath();
                Set<IResource> dependentResources = dependencies.getDependenciesOf(dependee);
                deps.addEntry(new Entry(dependeePath.toPortableString(), PropertyUtil.fromPaths(dependentResources.toArray(new IResource[0]))));    
            }
        }
    }

    private void saveBuildResult(Section resultSection) {
        resultSection.addEntry(new Entry("rebuild-needed", Boolean.toString(fullRebuildNeeded)));

        if (buildResult != null) {
            resultSection.addEntry(new Entry("variant", BuildVariant.toString(buildResult.getVariant())));
            resultSection.addEntry(new Entry("success", Boolean.toString(buildResult.success())));
            resultSection.addEntry(new Entry("timestamp", Long.toString(buildResult.getTimestamp())));
            File output = buildResult.getBuildResult();
            if (output != null) {
                resultSection.addEntry(new Entry("output", output.getAbsolutePath()));
            }
        }
    }

    private void saveFileState(Section files) {
        for (IPath path : tree.timestampMap.keySet()) {
            files.addEntry(new Entry(path.toPortableString(), tree.timestampMap.get(path).toString()));
        }
    }

    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#updateState(org.eclipse.core.resources.IResource)
     */
    public void updateState(IResource resource) throws CoreException {
        resource.accept(tree);
    }
    
    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#updateState(com.mobilesorcery.sdk.core.IFileTreeDiff)
     */
    public void updateState(IFileTreeDiff diff) {
        for (IPath added : diff.getAdded()) {
            tree.internalUpdateState(added);
        }
        
        for (IPath changed  : diff.getChanged()) {
            tree.internalUpdateState(changed);
        }
        
        for (IPath removed : diff.getRemoved()) {
            tree.removeState(removed);
        }
    }
    
    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#updateResult(com.mobilesorcery.sdk.core.IBuildResult)
     */
    public void updateResult(IBuildResult buildResult) {
        this.buildResult = buildResult;
    }
    
    public IBuildResult getBuildResult() {
        return buildResult;
    }
    
    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#getBuildVariant()
     */
    public IBuildVariant getBuildVariant() {
        return variant;
    }
    
    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#fullRebuildNeeded()
     */
    public boolean fullRebuildNeeded() {
        return fullRebuildNeeded;
    }
    
    public void fullRebuildNeeded(boolean fullRebuildNeeded) {
        this.fullRebuildNeeded = fullRebuildNeeded;
    }
    
    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#getDependencyManager()
     */
    public DependencyManager<IResource> getDependencyManager() {
        return dependencies;
    }

    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#createDiff()
     */
    public IFileTreeDiff createDiff() throws CoreException {
        FileInfoTree currentTree = new FileInfoTree();
        IProject project = this.project.getWrappedProject();
        project.accept(currentTree);
        return currentTree.computeDiff(tree);
    }

    /* (non-Javadoc)
     * @see com.mobilesorcery.sdk.internal.IBuildState#clear()
     */
    public void clear() {
        tree = new FileInfoTree();
        dependencies = new DependencyManager<IResource>();
        properties = new HashMap<String, String>();
        buildResult = null;
        fullRebuildNeeded = true;
    }

    public void updateBuildProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public Set<String> getChangedBuildProperties() {
        Map<String, String> currentBuildProperties = project.getProperties();
        Set<String> changed = new HashSet<String>();
        for (Map.Entry<String, String> entry : currentBuildProperties.entrySet()) {
            String key = entry.getKey();
            String currentValue = entry.getValue();
            String oldValue = project.getProperty(key);
            boolean bothEmpty = Util.isEmpty(currentValue) && Util.isEmpty(oldValue);
            if (!bothEmpty && !currentValue.equals(oldValue)) {
                changed.add(key);
            }
        }
        
        return changed;
    }

    public void updateBuildVariant(IBuildVariant variant) {
        this.variant = variant;
    }

}
