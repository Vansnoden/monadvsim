package com.monadvsim.app.controllers;

import com.monadvsim.app.models.Project;
import com.monadvsim.app.views.MainWindow;

public class MainController{
  
  private Project model = null;
  private MainWindow view = null;
  
  public MainController(Project model, MainWindow view){
    this.model = model;
    this.view = view;
    this.initListeners();
  }
  
  private void initListeners(){
    
  }

}
