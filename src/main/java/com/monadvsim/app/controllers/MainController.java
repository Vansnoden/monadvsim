package com.monadvsim.app.controllers;

import com.monadvsim.app.models.Project;
import com.monadvsim.app.views.MainWindow;
import com.monadvsim.app.views.DialogNewProject;
import javax.swing.JFileChooser;
import javax.swing.JDialog;
import java.util.List;
import java.util.Arrays;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;

public class MainController{
  
  private Project model = null;
  private MainWindow view = null;
  
  public MainController(Project model, MainWindow view){
    this.model = model;
    this.view = view;
    this.initListeners();
  }
  
  private void initListeners(){
    this.view.getBtnNewProject().addActionListener(l -> handleNewProject());
  }
  
  private void handleNewProject(){
    DialogNewProject dialog = new DialogNewProject();
    dialog.getBtnSelectFolder().addActionListener(l -> {
      File selectedFolder = openFileChooser(dialog, JFileChooser.DIRECTORIES_ONLY, null);
      System.out.println("Select Folder: "+selectedFolder.getName());
    });
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

}
