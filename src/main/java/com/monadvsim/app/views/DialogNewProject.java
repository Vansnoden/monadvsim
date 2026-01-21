package com.monadvsim.app.views;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.border.TitledBorder;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.FlowLayout;
import java.awt.Color;

public class DialogNewProject extends JDialog {

    private JPanel content = null;
    private JButton btnSelectFolder = null, btnSave = null;
    private JTextField projectNameField = null;
    private JLabel selectFolderLabel = null;

    public DialogNewProject() {
        this.setTitle("New Project");
        this.setSize(350, 200);
        this.initComponents();
        this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.setLocationRelativeTo(null);
        this.setVisible(true);
    }

    public JButton getBtnSelectFolder() {
        return this.btnSelectFolder;
    }

    public JButton getBtnSave() {
        return this.btnSave;
    }

    public JTextField getProjectNameField() {
        return this.projectNameField;
    }

    public JLabel getSelectFolderLabel() {
        return this.selectFolderLabel;
    }

    private void initComponents() {
        this.content = new JPanel(new BorderLayout(10, 10)); // Added gaps between border areas
        this.content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // Window padding
        this.btnSelectFolder = new JButton("Browse...");
        this.btnSave = new JButton("Save Project");
        this.projectNameField = new JTextField(20);
        this.selectFolderLabel = new JLabel("No folder selected");
        this.selectFolderLabel.setForeground(Color.GRAY);
        JPanel formFieldsPanel = new JPanel(new GridBagLayout());
        formFieldsPanel.setBorder(BorderFactory.createTitledBorder("Project Information"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8); // Consistent padding between elements
        gbc.fill = GridBagConstraints.HORIZONTAL;
        // --- ROW 0: Project Name ---
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0; // Label doesn't need extra space
        formFieldsPanel.add(new JLabel("Project Name:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0; // Field takes up all available horizontal space
        formFieldsPanel.add(this.projectNameField, gbc);
        // --- ROW 1: Folder Selection ---
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        formFieldsPanel.add(new JLabel("Location:"), gbc);
        // Nested panel to keep label and button on the same line in the right column
        JPanel folderPickerPanel = new JPanel(new BorderLayout(5, 0));
        folderPickerPanel.add(this.selectFolderLabel, BorderLayout.CENTER);
        folderPickerPanel.add(this.btnSelectFolder, BorderLayout.EAST);
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        formFieldsPanel.add(folderPickerPanel, gbc);
        // --- BOTTOM BUTTONS ---
        JPanel formButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        // Align save to right
        formButtonsPanel.add(this.btnSave);
        this.content.add(formFieldsPanel, BorderLayout.NORTH);
        // Use North so it doesn't stretch vertically
        this.content.add(formButtonsPanel, BorderLayout.SOUTH);
        this.add(content);
    }

}
