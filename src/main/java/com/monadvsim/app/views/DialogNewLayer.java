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
import javax.swing.JFrame;
import javax.swing.JSpinner;
import javax.swing.JCheckBox;
import java.awt.Insets;
import javax.swing.SpinnerNumberModel;
import java.awt.Frame;


public class DialogNewLayer extends JDialog {
  
  private JPanel content=null;
  private JComboBox layerTypeField=null;
  private JTextField layerNameField=null;
  private JSpinner populationSpinner=null;
  private JCheckBox wrapAroundCheck=null;
  private JButton btnSave=null;
  private boolean succeeded = false;
  private String[] layerOptions = {"Vector Layer", "Raster Layer", "Agent Layer"};

  public DialogNewLayer(Frame parent){
    super(parent, "Add New Layer", true);
    this.setTitle("Add Layer");
    this.setSize(350,300);
    this.initComponents();
    this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    this.setLocationRelativeTo(parent);
    this.setVisible(true);
  }
  
  public JComboBox getLayerTypeField(){
    return this.layerTypeField;
  }
  
  public boolean isSucceeded() { return succeeded; }
  
  public String getLayerType() { return (String) this.layerTypeField.getSelectedItem(); }
  
  public String getLayerName() { return this.layerNameField.getText().trim(); }
  
  public int getPopulation() { return (int) populationSpinner.getValue(); }
  
  public boolean isWrapAround() { return wrapAroundCheck.isSelected(); }
  
  public JButton getBtnSave(){ return this.btnSave; }
  
  public JTextField getLayerNameField(){ return this.layerNameField; }
  
  private void initComponents(){
    this.content = new JPanel(new BorderLayout(10, 10));
    this.content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); 
    this.populationSpinner = new JSpinner(new SpinnerNumberModel(1000, 0, 10000000, 1000));
    this.layerTypeField = new JComboBox<>(this.layerOptions);
    this.wrapAroundCheck = new JCheckBox("Enabled");
    this.layerTypeField.addActionListener(e -> {
      boolean isAgent = layerTypeField.getSelectedItem().equals("Agent Layer");
      populationSpinner.setEnabled(isAgent);
      wrapAroundCheck.setEnabled(isAgent);
    });
    // Initialize state
    boolean isAgent = this.layerTypeField.getSelectedItem().equals("Agent Layer");
    this.populationSpinner.setEnabled(isAgent);
    this.wrapAroundCheck.setEnabled(isAgent);
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
    
    JPanel agentLayerSettings = new JPanel(new GridBagLayout());
    agentLayerSettings.setBorder(BorderFactory.createTitledBorder("Agent Layer Details"));
    GridBagConstraints gbc2 = new GridBagConstraints();
    gbc2.insets = new Insets(8, 8, 8, 8); // Consistent padding between elements
    gbc2.fill = GridBagConstraints.HORIZONTAL;
    gbc2.gridx = 0; gbc2.gridy = 0;
    gbc2.weightx = 0; // Label doesn't need extra space
    agentLayerSettings.add(new JLabel("Population (Agents):"), gbc2);
    gbc2.gridx = 1; gbc2.gridy = 0;
    gbc2.weightx = 0;
    agentLayerSettings.add(populationSpinner, gbc2);
    gbc2.gridx = 0; gbc2.gridy = 1;
    gbc2.weightx = 0;
    agentLayerSettings.add(new JLabel("World Wrap:"), gbc2);
    gbc2.gridx = 1; gbc2.gridy = 1;
    gbc2.weightx = 0;
    agentLayerSettings.add(wrapAroundCheck, gbc2);
    
    JPanel formButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); // Align save to right
    formButtonsPanel.add(this.btnSave);
    this.content.add(formFieldsPanel, BorderLayout.NORTH);
    this.content.add(agentLayerSettings, BorderLayout.CENTER);// Use North so it doesn't stretch vertically
    this.content.add(formButtonsPanel, BorderLayout.SOUTH);
    this.add(content);
  }
}
