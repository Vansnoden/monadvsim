package com.monadvsim.app.controllers;

import com.monadvsim.app.models.Project;
import com.monadvsim.app.models.ProjectPersistenceService;
import com.monadvsim.app.views.MainWindow;
import com.monadvsim.app.views.DialogNewProject;
import com.monadvsim.app.views.DialogNewLayer;
import javax.swing.JFileChooser;
import javax.swing.JDialog;
import java.util.List;
import java.util.Arrays;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import javax.swing.JOptionPane;
import org.geotools.swing.tool.PanTool;
import org.geotools.swing.tool.ZoomInTool;
import org.geotools.swing.tool.ZoomOutTool;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MainController{
  
  private Project project = null;
  private MainWindow view = null;
  private ProjectPersistenceService projectPersistenceService = null;
  
  public MainController(Project project, MainWindow view){
    this.project = project;
    this.view = view;
    this.projectPersistenceService = new ProjectPersistenceService();
    this.initListeners();
  }
  
  private void initListeners(){
    this.view.getBtnNewProject().addActionListener(l -> handleNewProject());
    this.view.getBtnAddLayer().addActionListener(l -> handleNewLayer());
  }
  
  private void handleNewProject(){
    DialogNewProject dialog = new DialogNewProject();
    dialog.getBtnSelectFolder().addActionListener(l -> {
      File selectedFolder = openFileChooser(dialog, JFileChooser.DIRECTORIES_ONLY, null);
      dialog.getSelectFolderLabel().setText(selectedFolder.getAbsolutePath());
    });
    dialog.getBtnSave().addActionListener(l -> {
    try {
      String name = dialog.getProjectNameField().getText();
      File pFile = new File(dialog.getSelectFolderLabel().getText(), name + ".mvsim");
      project = new Project();
      project.setName(name);
      project.setCrs("EPSG:4326"); 
      project.setProjectFile(pFile);
      projectPersistenceService.saveProject(project, pFile);
      view.setTitle("MonadVSIM - " + name);
      dialog.dispose();
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(dialog, "New project creation failed: " + ex.getMessage());
    }
      dialog.dispose();
    });
  }
  
  private void handleSaveProject() throws Exception {
    if (project.getProjectFile() == null) { 
      handleNewProject();
    } else {
      projectPersistenceService.saveProject(project, project.getProjectFile());
      JOptionPane.showMessageDialog(view, "Project Saved Successfully."); 
    }
  }
  
  private void handleNewLayer(){
    DialogNewLayer dialog = new DialogNewLayer(view);
    if (dialog.isSucceeded()) { 
      if (dialog.getLayerType().equals("Agent Layer")) {
        handleCreateAgentLayer(dialog.getLayerName(), dialog.getPopulation(), dialog.isWrapAround());
      } else {
        handleCreateSpatialLayer(dialog.getLayerType(), dialog.getLayerName());
      }
    }
  }
  
  private File openFileChooser(JDialog parent, int mode, List<String> ext){
    File selectedFile = null;
    if( mode == JFileChooser.DIRECTORIES_ONLY ){
      selectedFile = openFileChooserFolderMode(parent, mode);
    }else{
      selectedFile = openFileChooserFileMode(parent, mode, ext);
    }
    return selectedFile;
  }
  
  private File openFileChooserFolderMode(JDialog parent, int mode){
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(mode);
    chooser.setAcceptAllFileFilterUsed(false);
    int result = chooser.showOpenDialog(parent);
    if (result == JFileChooser.APPROVE_OPTION) {
        File selectedFolder = chooser.getSelectedFile();
        return selectedFolder;
    }
    return null;
  }
  
  private File openFileChooserFileMode(JDialog parent, int mode, List<String> extensions){
    JFileChooser chooser = new JFileChooser();
    FileNameExtensionFilter filter = new FileNameExtensionFilter(
        "Supported Files (" + String.join(", ", extensions) + ")", 
        extensions.toArray(new String[0])
    );
    chooser.setFileFilter(filter);
    int returnVal = chooser.showOpenDialog(parent);
    if(returnVal == JFileChooser.APPROVE_OPTION) {
        return chooser.getSelectedFile();
    }
    return null;
  }
  
  private void refreshUI() {
    view.updateLayerTree(project.getLayers());
    view.getSimulationCanvas().updateLayers(project.getLayers(), project.getProjectFile()); 
  }
  
  private void toggleLayerVisibility(Layer layer) {
    layer.setVisible(!layer.isVisible());
    refreshUI();
  }

  private void handleCreateAgentLayer(String name, int population, boolean wrap) {
  AgentLayer al = new AgentLayer(name);
  al.setWrapAround(wrap); 
  al.setPopulation(population, project);
  project.getLayers().add(al);
  refreshUI(); 
  }

  private void handleCreateSpatialLayer(String type, String name) {
  JFileChooser chooser = new JFileChooser();
  if (chooser.showOpenDialog(view) == JFileChooser.APPROVE_OPTION) {
  File layerFile = chooser.getSelectedFile();
  Layer newLayer = type.equals("Vector Layer") ? 
        new VectorLayer(name, layerFile.getAbsolutePath()) : new RasterLayer(name, layerFile.getAbsolutePath());
  project.getLayers().add(newLayer);
  refreshUI();
  } 
  }

}
