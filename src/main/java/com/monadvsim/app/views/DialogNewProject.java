package com.monadvsim.app.views;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JLabel;


public class DialogNewProject extends JDialog{
  
  private JPanel content=null;
  private JButton btnSelectFolder=null, btnSave=null;

  public DialogNewProject(){
    this.setTitle("New Project");
    this.setSize(350,200);
    this.initComponents();
    this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    this.setLocationRelativeTo(null);
    this.setVisible(true);
  }
  
  public JButton getBtnSelectFolder(){
    return this.btnSelectFolder;
  }
  
  private void initComponents(){
    this.btnSelectFolder = new JButton();
  }
}


