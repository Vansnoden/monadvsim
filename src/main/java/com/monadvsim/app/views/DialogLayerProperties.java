package com.monadvsim.app.views;

import com.monadvsim.app.models.*;
import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.JLabel;
import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JSpinner;
import java.awt.Frame;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.Color;
import javax.swing.JPanel;
import javax.swing.BorderFactory;
import javax.swing.JOptionPane;
import javax.swing.JSlider;
import javax.swing.JComboBox;
import javax.swing.JColorChooser;
import javax.swing.SpinnerNumberModel;


public class DialogLayerProperties extends JDialog {
    private final Layer layer;
    private boolean confirmed = false;
    private boolean deleteRequested = false;

    private JTextField nameField;
    private JCheckBox visibleCheck;
    private JSlider opacitySlider;
    private JComboBox<String> crsBox;
    
    // Agent-specific components
    private JSpinner popSpinner;
    private JCheckBox wrapCheck;
    private JCheckBox heatCheck;
    private JCheckBox trailCheck;
    private JButton colorBtn;

    public DialogLayerProperties(Frame owner, Layer layer) {
        super(owner, "Properties: " + layer.getName(), true);
        this.layer = layer;
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout());
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL; 
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.gridx = 0; gbc.gridy = 0;

        // 1. Common Properties (Applies to Vector, Raster, and Agent Layers)
        mainPanel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1; nameField = new JTextField(layer.getName(), 15); mainPanel.add(nameField, gbc);

        gbc.gridx = 0; gbc.gridy++; mainPanel.add(new JLabel("Opacity:"), gbc);
        gbc.gridx = 1; opacitySlider = new JSlider(0, 100, (int)(layer.getOpacity() * 100)); mainPanel.add(opacitySlider, gbc);

        gbc.gridx = 0; gbc.gridy++; mainPanel.add(new JLabel("Visible:"), gbc);
        gbc.gridx = 1; visibleCheck = new JCheckBox("", layer.isVisible()); mainPanel.add(visibleCheck, gbc);

        gbc.gridx = 0; gbc.gridy++; mainPanel.add(new JLabel("CRS:"), gbc);
        gbc.gridx = 1; crsBox = new JComboBox<>(new String[]{"EPSG:4326", "EPSG:3857"});
        crsBox.setSelectedItem(layer.getCrsCode()); mainPanel.add(crsBox, gbc);

        // 2. Agent Specific Properties (Dynamic UI)
        if (layer instanceof AgentLayer al) {
            gbc.gridx = 0; gbc.gridy++; mainPanel.add(new JLabel("Agent Color:"), gbc);
            gbc.gridx = 1; 
            colorBtn = new JButton(" "); 
            try {
                colorBtn.setBackground(Color.decode(al.getColorHex()));
            } catch (Exception e) {
                colorBtn.setBackground(Color.GREEN);
            }
            colorBtn.addActionListener(e -> {
                Color c = JColorChooser.showDialog(this, "Select Agent Color", colorBtn.getBackground());
                if (c != null) colorBtn.setBackground(c);
            });
            mainPanel.add(colorBtn, gbc);

            gbc.gridx = 0; gbc.gridy++; mainPanel.add(new JLabel("Population:"), gbc);
            gbc.gridx = 1; 
            popSpinner = new JSpinner(new SpinnerNumberModel(al.getAgentsCount(), 0, 10000000, 1000));
            mainPanel.add(popSpinner, gbc);

            gbc.gridx = 0; gbc.gridy++; mainPanel.add(new JLabel("Sim Options:"), gbc);
            gbc.gridx = 1; 
            wrapCheck = new JCheckBox("Wrap", al.isWrapAround());
            heatCheck = new JCheckBox("Heatmap", al.isHeatmapEnabled());
            trailCheck = new JCheckBox("Trails", al.isTrailsEnabled());
            
            JPanel sub = new JPanel(new FlowLayout(FlowLayout.LEFT)); 
            sub.add(wrapCheck); sub.add(heatCheck); sub.add(trailCheck); 
            mainPanel.add(sub, gbc);
        }

        // 3. Action Buttons
        JPanel btnPanel = new JPanel(new BorderLayout());
        btnPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        
        JButton deleteBtn = new JButton("Remove Layer");
        deleteBtn.setForeground(Color.RED);
        deleteBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this, "Delete this layer?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                deleteRequested = true;
                confirmed = true;
                dispose();
            }
        });

        JPanel rightBtns = new JPanel();
        JButton okBtn = new JButton("Apply Changes");
        okBtn.addActionListener(e -> {
            if (nameField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Name cannot be empty");
                return;
            }
            confirmed = true;
            dispose();
        });
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        
        rightBtns.add(cancelBtn);
        rightBtns.add(okBtn);

        btnPanel.add(deleteBtn, BorderLayout.WEST);
        btnPanel.add(rightBtns, BorderLayout.EAST);

        add(mainPanel, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);
        
        pack();
        setLocationRelativeTo(getOwner());
    }

    // --- Data Accessors ---
    public boolean isConfirmed() { return confirmed; }
    public boolean isDeleteRequested() { return deleteRequested; }
    public String getLayerName() { return nameField.getText().trim(); }
    public float getOpacity() { return opacitySlider.getValue() / 100.0f; }
    public boolean isVisible() { return visibleCheck.isSelected(); }
    public String getSelectedCrs() { return (String) crsBox.getSelectedItem(); }
    
    public int getPopulation() { 
        return (popSpinner != null) ? (int) popSpinner.getValue() : 0; 
    }
    
    public boolean isWrap() { 
        return wrapCheck != null && wrapCheck.isSelected(); 
    }
    
    // Method renamed from isHeat() to isHeatmapEnabled() to match Controller
    public boolean isHeatmapEnabled() { 
        return heatCheck != null && heatCheck.isSelected(); 
    }
    
    // Method renamed from isTrails() to isTrailsEnabled() to fix the compilation error
    public boolean isTrailsEnabled() { 
        return trailCheck != null && trailCheck.isSelected(); 
    }

    public String getColorHex() {
        if (colorBtn == null) return "#00FF00";
        Color c = colorBtn.getBackground();
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
