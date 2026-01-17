package com.monadvsim.app.views;

import javax.swing.JDialog;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JLabel;
import javax.swing.BorderFactory;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.FlowLayout;
import javax.swing.JButton;
import java.awt.Insets;


public class DialogNewLayer extends JDialog {
  
  private JPanel content=null;
  private JComboBox layerTypeField=null;
  private JTextField layerNameField=null;
  private JButton btnSave=null;
  private String[] layerOptions = {"Vector Layer", "Raster Layer", "Agents Layer"};

  public DialogNewLayer(){
    this.setTitle("Add Layer");
    this.setSize(350,200);
    this.initComponents();
    this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    this.setLocationRelativeTo(null);
    this.setVisible(true);
  }
  
  private void initComponents(){
    this.content = new JPanel(new BorderLayout(10, 10));
    this.content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); 
    this.layerTypeField = new JComboBox<>(this.layerOptions);
    this.layerNameField = new JTextField(30);
    this.btnSave = new JButton("Add New Layer");
    JPanel formFieldsPanel = new JPanel(new GridBagLayout());
    formFieldsPanel.setBorder(BorderFactory.createTitledBorder("Layer Information"));
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(8, 8, 8, 8); // Consistent padding between elements
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.gridx = 0; gbc.gridy = 0;
    gbc.weightx = 0; // Label doesn't need extra space
    formFieldsPanel.add(new JLabel("Layer Type:"), gbc);
    gbc.gridx = 1; gbc.gridy = 0;
    gbc.weightx = 1.0; // Field takes up all available horizontal space
    formFieldsPanel.add(this.layerTypeField, gbc);
    gbc.gridx = 0; gbc.gridy = 1;
    gbc.weightx = 0;
    formFieldsPanel.add(new JLabel("Layer Name:"), gbc);
    gbc.gridx = 1; gbc.gridy = 1;
    gbc.weightx = 1.0;
    formFieldsPanel.add(this.layerNameField, gbc);
    JPanel formButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); // Align save to right
    formButtonsPanel.add(this.btnSave);
    this.content.add(formFieldsPanel, BorderLayout.NORTH); // Use North so it doesn't stretch vertically
    this.content.add(formButtonsPanel, BorderLayout.SOUTH);
    this.add(content);
  }
}
