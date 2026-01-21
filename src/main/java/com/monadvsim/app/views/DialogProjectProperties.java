package com.monadvsim.app.views;

import java.awt.Frame;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.BorderFactory;
import javax.swing.JOptionPane;



public class DialogProjectProperties extends JDialog {

  private JTextField nameField;
  private JComboBox<String> crsComboBox;
  private JButton btnApply;


  public DialogProjectProperties(Frame parent, String currentName, String currentCrs) {
    super(parent, "Project Properties", true);
    setLayout(new GridLayout(3, 2, 10, 10));
    add(new JLabel("Project Name:"));
    nameField = new JTextField(currentName);
    add(nameField);
    add(new JLabel("Coordinate System (CRS):"));
    // Example list of common EPSG codes
    String[] crsOptions = {"EPSG:4326", "EPSG:3857", "EPSG:32633"};
    crsComboBox = new JComboBox<>(crsOptions);
    crsComboBox.setSelectedItem(currentCrs);
    add(crsComboBox);
    btnApply = new JButton("Apply Changes");
    add(new JLabel("")); // Empty cell
    add(btnApply);
    pack();
    setLocationRelativeTo(parent);
  }


  public String getProjectName() { return nameField.getText(); }
  
  
  public String getSelectedCrs() { return (String) crsComboBox.getSelectedItem(); }
  
  
  public JButton getBtnApply() { return btnApply; }
  
}
