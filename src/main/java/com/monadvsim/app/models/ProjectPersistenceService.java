package com.monadvsim.app.models;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;



public class ProjectPersistenceService {

  private final XmlMapper xmlMapper;

  public ProjectPersistenceService() {
    this.xmlMapper = new XmlMapper();
    this.xmlMapper.registerModule(new JavaTimeModule());
    this.xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
    this.xmlMapper.configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true);
  }


  public void saveProject(Project project, File file) throws IOException {
    if (project == null || file == null) {
      throw new IllegalArgumentException("Project or File cannot be null");
    }
    Map<Layer, String> originalPaths = new HashMap<>();
    File projectDir = file.getParentFile();
    for (Layer layer : project.getLayers()) {
      String path = layer.getRelativePath();
      if (path != null && !path.startsWith("internal://")) {
        originalPaths.put(layer, path);
        File f = new File(path);
        if (f.isAbsolute()) {
            layer.setRelativePath(makeRelative(projectDir, f));
        }
      }
    }
    try {
      xmlMapper.writeValue(file, project);
      project.setProjectFile(file);
    } finally {
      originalPaths.forEach(Layer::setRelativePath);
    }
  }

  
  public Project loadProject(File file) throws IOException {
    if (file == null || !file.exists()) {
      throw new IOException("Project file does not exist: " + file);
    }
    Project project = xmlMapper.readValue(file, Project.class);
    project.setProjectFile(file);
    return project;
  }

  
  private String makeRelative(File baseDir, File absoluteFile) {
    try {
      return baseDir.toURI().relativize(absoluteFile.toURI()).getPath();
    } catch (Exception e) {
      return absoluteFile.getAbsolutePath();
    }
  }
}
