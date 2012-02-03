package com.intellij.flex.uiDesigner.libraries;

import com.intellij.ProjectTopics;
import com.intellij.diagnostic.errordialog.Attachment;
import com.intellij.flex.uiDesigner.*;
import com.intellij.flex.uiDesigner.io.IdPool;
import com.intellij.flex.uiDesigner.io.InfoMap;
import com.intellij.flex.uiDesigner.io.RetainCondition;
import com.intellij.flex.uiDesigner.io.StringRegistry;
import com.intellij.flex.uiDesigner.libraries.FlexLibrarySet.ContainsCondition;
import com.intellij.flex.uiDesigner.libraries.LibrarySorter.SortResult;
import com.intellij.flex.uiDesigner.mxml.ProjectDocumentReferenceCounter;
import com.intellij.lang.javascript.flex.FlexUtils;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.StringBuilderSpinAllocator;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

@SuppressWarnings("MethodMayBeStatic")
public class LibraryManager {
  private static final String SWF_EXTENSION = ".swf";
  static final String PROPERTIES_EXTENSION = ".properties";

  //private static final String ABC_FILTER_VERSION = "16";
  //private static final String ABC_FILTER_VERSION_VALUE_NAME = "fud_abcFilterVersion";

  private File appDir;

  private final InfoMap<VirtualFile, Library> libraries = new InfoMap<VirtualFile, Library>();

  private final THashMap<String, LibrarySet> librarySets = new THashMap<String, LibrarySet>();
  private final IdPool librarySetIdPool = new IdPool();
  private final Map<VirtualFile, Set<CharSequence>> globalDefinitionsMap = new THashMap<VirtualFile, Set<CharSequence>>();

  public void unregister(final int[] ids) {
    librarySets.retainEntries(new RetainCondition<String, LibrarySet>(ids));
    librarySetIdPool.dispose(ids);
  }

  public static LibraryManager getInstance() {
    return DesignerApplicationManager.getService(LibraryManager.class);
  }

  public void setAppDir(@NotNull File appDir) {
    this.appDir = appDir;
  }

  public boolean isRegistered(@NotNull Library library) {
    return libraries.contains(library);
  }

  public int add(@NotNull Library library) {
    return libraries.add(library);
  }

  public void garbageCollection(@SuppressWarnings("UnusedParameters") ProgressIndicator indicator) {
  }

  public ProjectDocumentReferenceCounter initLibrarySets(@NotNull final Module module,
                                                         boolean collectLocalStyleHolders,
                                                         ProblemsHolder problemsHolder) throws InitException {
    final Project project = module.getProject();
    final StringRegistry.StringWriter stringWriter = new StringRegistry.StringWriter(16384);
    stringWriter.startChange();
    final AssetCounter assetCounter = new AssetCounter();
    final LibraryCollector libraryCollector = new LibraryCollector(this, new LibraryStyleInfoCollector(assetCounter, problemsHolder, module, stringWriter), module);
    final Client client;
    try {
      final AccessToken token = ReadAction.start();
      try {
        libraryCollector.collect(module);
      }
      finally {
        token.finish();
      }

      client = Client.getInstance();
      if (stringWriter.hasChanges()) {
        client.updateStringRegistry(stringWriter);
      }
      else {
        stringWriter.finishChange();
      }
    }
    catch (Throwable e) {
      stringWriter.rollbackChange();
      throw new InitException(e, "error.collect.libraries");
    }

    assert !libraryCollector.sdkLibraries.isEmpty();
    final FlexLibrarySet flexLibrarySet = getOrCreateFlexLibrarySet(libraryCollector, assetCounter);
    final InfoMap<Project, ProjectInfo> registeredProjects = client.getRegisteredProjects();
    ProjectInfo info = registeredProjects.getNullableInfo(project);
    if (info == null) {
      info = new ProjectInfo(project);
      registeredProjects.add(info);
      client.openProject(project);
    }

    LibrarySet librarySet;
    if (libraryCollector.externalLibraries.isEmpty()) {
      librarySet = null;
    }
    else {
      final String key = createKey(libraryCollector.externalLibraries);
      librarySet = librarySets.get(key);
      if (librarySet == null) {
        final int id = librarySetIdPool.allocate();
        final SortResult sortResult = sortLibraries(new LibrarySorter(), id, libraryCollector.externalLibraries,
          libraryCollector.getFlexSdkVersion(), flexLibrarySet.contains);
        librarySet = new LibrarySet(id, flexLibrarySet, sortResult.items);
        registerLibrarySet(key, librarySet);
      }
    }

    final ModuleInfo moduleInfo = new ModuleInfo(module,
      Collections.singletonList(librarySet == null ? flexLibrarySet : librarySet), ModuleInfoUtil.isApp(module));
    final ProjectDocumentReferenceCounter projectDocumentReferenceCounter = new ProjectDocumentReferenceCounter();
    if (collectLocalStyleHolders) {
      // client.registerModule finalize it
      stringWriter.startChange();
      try {
        ModuleInfoUtil.collectLocalStyleHolders(moduleInfo, libraryCollector.getFlexSdkVersion(), stringWriter, problemsHolder,
          projectDocumentReferenceCounter, assetCounter);
      }
      catch (Throwable e) {
        stringWriter.rollbackChange();
        throw new InitException(e, "error.collect.local.style.holders");
      }
    }

    client.registerModule(project, moduleInfo, stringWriter);
    client.fillAssetClassPoolIfNeed(flexLibrarySet);

    module.getMessageBus().connect(moduleInfo).subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
      @Override
      public void beforeRootsChange(ModuleRootEvent event) {
      }

      @Override
      public void rootsChanged(ModuleRootEvent event) {
        new Notification(FlashUIDesignerBundle.message("plugin.name"), FlashUIDesignerBundle.message("plugin.name"),
          "Please reopen your project to update on library changes.",
          NotificationType.WARNING).notify(project);
      }
    });

    return projectDocumentReferenceCounter;
  }

  private FlexLibrarySet getOrCreateFlexLibrarySet(LibraryCollector libraryCollector, AssetCounter assetCounter) throws InitException {
    final String key = createKey(libraryCollector.sdkLibraries);
    FlexLibrarySet flexLibrarySet = (FlexLibrarySet)librarySets.get(key);
    if (flexLibrarySet == null) {
      final Set<CharSequence> globalDefinitions = getGlobalDefinitions(libraryCollector.getGlobalLibrary());
      final int id = librarySetIdPool.allocate();
      Condition<String> globalContains = new Condition<String>() {
        @Override
        public boolean value(String name) {
          return globalDefinitions.contains(name);
        }
      };
      final SortResult sortResult = sortLibraries(
        new LibrarySorter(new FlexDefinitionProcessor(libraryCollector.getFlexSdkVersion()), new FlexDefinitionMapProcessor(
          libraryCollector.getFlexSdkVersion(), globalContains)), id, libraryCollector.sdkLibraries,
        libraryCollector.getFlexSdkVersion(),
        globalContains);

      flexLibrarySet = new FlexLibrarySet(id, null, sortResult.items, new ContainsCondition(globalDefinitions, sortResult.definitionMap), assetCounter);
      registerLibrarySet(key, flexLibrarySet);
    }

    return flexLibrarySet;
  }

  private void registerLibrarySet(String key, LibrarySet librarySet) {
    Client.getInstance().registerLibrarySet(librarySet);
    librarySets.put(key, librarySet);
  }

  private Set<CharSequence> getGlobalDefinitions(VirtualFile file) throws InitException {
    Set<CharSequence> globalDefinitions = globalDefinitionsMap.get(file);
    if (globalDefinitions == null) {
      try {
        globalDefinitions = LibraryUtil.getDefinitions(file);
      }
      catch (IOException e) {
        throw new InitException(e, "error.sort.libraries");
      }
    }
    
    globalDefinitionsMap.put(file, globalDefinitions);
    return globalDefinitions;
  }

  private String createKey(List<Library> libraries) {
    // we don't depend on library order
    final String[] filenames = new String[libraries.size()];
    for (int i = 0, librariesSize = libraries.size(); i < librariesSize; i++) {
      filenames[i] = libraries.get(i).getFile().getPath();
    }
    
    Arrays.sort(filenames);
    
    final StringBuilder stringBuilder = StringBuilderSpinAllocator.alloc();
    try {
      for (String filename : filenames) {
        stringBuilder.append(filename).append(':');
      }

      return stringBuilder.toString();
    }
    finally {
      StringBuilderSpinAllocator.dispose(stringBuilder);
    }
  }

  @NotNull
  private SortResult sortLibraries(LibrarySorter librarySorter, int librarySetId, List<Library> libraries, String flexSdkVersion,
                                   Condition<String> isExternal)
    throws InitException {
    try {
      return librarySorter.sort(libraries, new File(appDir, librarySetId + SWF_EXTENSION), isExternal);
    }
    catch (Throwable e) {
      String technicalMessage = "Flex SDK " + flexSdkVersion;
      final Attachment[] attachments = new Attachment[libraries.size()];
      try {
        for (int i = 0, librariesSize = libraries.size(); i < librariesSize; i++) {
          attachments[i] = new Attachment(libraries.get(i).getCatalogFile());
        }
      }
      catch (Throwable innerE) {
        technicalMessage += " Cannot collect library catalog files due to " + ExceptionUtil.getThrowableText(innerE);
      }

      throw new InitException(e, "error.sort.libraries", attachments, technicalMessage);
    }
  }

  // created library will be register later, in Client.registerLibrarySet, so, we expect that createOriginalLibrary never called with duplicated virtualFile, i.e.
  // sdkLibraries doesn't contain duplicated virtualFiles and externalLibraries too (http://youtrack.jetbrains.net/issue/AS-200)
  Library createOriginalLibrary(@NotNull final VirtualFile jarFile, @NotNull final LibraryStyleInfoCollector processor) {
    Library info = libraries.getNullableInfo(jarFile);
    final boolean isNew = info == null;
    if (isNew) {
      info = new Library(jarFile);
    }
    processor.process(info, isNew);
    return info;
  }

  @SuppressWarnings("MethodMayBeStatic")
  @Nullable
  public PropertiesFile getResourceBundleFile(String locale, String bundleName, ModuleInfo moduleInfo) {
    final Project project = moduleInfo.getElement().getProject();
    for (LibrarySet librarySet : moduleInfo.getLibrarySets()) {
      do {
        PropertiesFile propertiesFile;
        for (Library library : librarySet.getLibraries()) {
          if (library.hasResourceBundles() && (propertiesFile = getResourceBundleFile(locale, bundleName, library, project)) != null) {
            return propertiesFile;
          }
        }
      }
      while ((librarySet = librarySet.getParent()) != null);
    }

    // AS-273
    final Sdk sdk = FlexUtils.getFlexSdkForFlexModuleOrItsFlexFacets(moduleInfo.getModule());
    VirtualFile dir = sdk == null ? null : sdk.getHomeDirectory();
    if (dir != null) {
      dir = dir.findFileByRelativePath("frameworks/projects");
    }

    if (dir != null) {
      for (String libName : new String[]{"framework", "spark", "mx", "airframework", "rpc", "advancedgrids", "charts", "textLayout"}) {
        VirtualFile file = dir.findFileByRelativePath(libName + "/bundles/" + locale + "/" + bundleName + PROPERTIES_EXTENSION);
        if (file != null) {
          return virtualFileToProperties(project, file);
        }
      }
    }

    return null;
  }

  private static PropertiesFile getResourceBundleFile(String locale, String bundleName, Library library, Project project) {
    final THashSet<String> bundles = library.resourceBundles.get(locale);
    if (!bundles.contains(bundleName)) {
      return null;
    }

    //noinspection ConstantConditions
    VirtualFile file = library.getFile().findChild("locale").findChild(locale).findChild(bundleName + PROPERTIES_EXTENSION);
    //noinspection ConstantConditions
    return virtualFileToProperties(project, file);
  }

  private static PropertiesFile virtualFileToProperties(Project project, VirtualFile file) {
    Document document = FileDocumentManager.getInstance().getDocument(file);
    assert document != null;
    return (PropertiesFile)PsiDocumentManager.getInstance(project).getPsiFile(document);
  }
}