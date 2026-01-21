package com.monadvsim.app.views;

import com.monadvsim.app.models.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class DialogRuleEditor extends JDialog {

    private final AgentLayer agentLayer;
    private final Project project;
    private JTable movementTable, rasterTable, responseTable;
    private DefaultTableModel movementTableModel, rasterTableModel, responseTableModel;
    private JSpinner speedSpinner;
    private JList<String> ruleList;
    private DefaultListModel<String> ruleListModel;
    private int selectedRuleIndex = 0;

    public DialogRuleEditor(JFrame parent, AgentLayer layer, Project project) {
        super(parent, "Rule Manager: " + layer.getName(), true);
        this.agentLayer = layer;
        this.project = project;
        initComponents();
        refreshRuleList();
        if (!agentLayer.getRules().isEmpty()) {
            ruleList.setSelectedIndex(0);
            loadRuleData(0);
        }
        setSize(850, 600);
        setLocationRelativeTo(parent);
    }

    private void initComponents() {
        setLayout(new BorderLayout(10, 10));

        // Left panel - Rules stack
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Rules Stack"));
        leftPanel.setPreferredSize(new Dimension(180, 0));

        ruleListModel = new DefaultListModel<>();
        ruleList = new JList<>(ruleListModel);
        ruleList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                saveCurrentToMemory();
                int idx = ruleList.getSelectedIndex();
                if (idx >= 0) {
                    selectedRuleIndex = idx;
                    loadRuleData(idx);
                }
            }
        });

        JPanel ruleButtons = new JPanel(new GridLayout(1, 2));
        JButton btnAdd = new JButton("Add");
        JButton btnDel = new JButton("Remove");

        btnAdd.addActionListener(e -> {
            agentLayer.addRule(new AgentRule());
            refreshRuleList();
            ruleList.setSelectedIndex(agentLayer.getRules().size() - 1);
        });

        btnDel.addActionListener(e -> {
            int idx = ruleList.getSelectedIndex();
            if (idx >= 0 && agentLayer.getRules().size() > 1) {
                agentLayer.getRules().remove(idx);
                refreshRuleList();
                ruleList.setSelectedIndex(0);
            } else if (idx >= 0 && agentLayer.getRules().size() == 1) {
                JOptionPane.showMessageDialog(this,
                        "Cannot delete the last rule. At least one rule is required.",
                        "Cannot Delete", JOptionPane.WARNING_MESSAGE);
            }
        });

        ruleButtons.add(btnAdd);
        ruleButtons.add(btnDel);

        leftPanel.add(new JScrollPane(ruleList), BorderLayout.CENTER);
        leftPanel.add(ruleButtons, BorderLayout.SOUTH);
        add(leftPanel, BorderLayout.WEST);

        // Center panel - Rule configuration
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));

        // Top settings panel
        JPanel topSettings = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topSettings.add(new JLabel("Base Speed (units/tick):"));

        speedSpinner = new JSpinner(new SpinnerNumberModel(
                agentLayer.getBaseSpeed(), 0.0, 1000.0, 0.01));
        speedSpinner.setPreferredSize(new Dimension(80, 25));
        topSettings.add(speedSpinner);

        centerPanel.add(topSettings, BorderLayout.NORTH);

        // Tabbed pane for different rule aspects
        JTabbedPane tabs = new JTabbedPane();

        // 1. Movement/Terrain tab
        movementTableModel = new DefaultTableModel(
                new Object[]{"Terrain Type", "Traversable"}, 0) {
            @Override
            public Class<?> getColumnClass(int col) {
                return col == 1 ? Boolean.class : String.class;
            }
        };
        movementTable = new JTable(movementTableModel);
        movementTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        movementTable.getColumnModel().getColumn(1).setPreferredWidth(100);

        // Add some default terrains
        movementTableModel.addRow(new Object[]{"land", true});
        movementTableModel.addRow(new Object[]{"water", false});
        movementTableModel.addRow(new Object[]{"forest", true});
        movementTableModel.addRow(new Object[]{"urban", true});
        movementTableModel.addRow(new Object[]{"empty", false});

        JPanel movementPanel = new JPanel(new BorderLayout());
        JButton addTerrainBtn = new JButton("Add Terrain");
        addTerrainBtn.addActionListener(e -> {
            String terrain = JOptionPane.showInputDialog(this, "Enter terrain type:");
            if (terrain != null && !terrain.trim().isEmpty()) {
                movementTableModel.addRow(new Object[]{terrain.trim(), true});
            }
        });

        JPanel movementBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        movementBtnPanel.add(addTerrainBtn);

        movementPanel.add(new JScrollPane(movementTable), BorderLayout.CENTER);
        movementPanel.add(movementBtnPanel, BorderLayout.SOUTH);
        tabs.addTab("Movement/Terrain", movementPanel);

        // 2. Environmental Risks tab
        rasterTableModel = new DefaultTableModel(
                new Object[]{"Raster Layer", "Lethal if Value >", "Lethal if Value <"}, 0);
        rasterTable = new JTable(rasterTableModel);
        rasterTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        rasterTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        rasterTable.getColumnModel().getColumn(2).setPreferredWidth(120);

        // Set cell editor for numeric columns in raster table
        TableCellEditor numericEditor = createNumericCellEditor();
        rasterTable.getColumnModel().getColumn(1).setCellEditor(numericEditor);
        rasterTable.getColumnModel().getColumn(2).setCellEditor(numericEditor);

        tabs.addTab("Environmental Risks", new JScrollPane(rasterTable));

        // 3. Environmental Responses tab
        responseTableModel = new DefaultTableModel(
                new Object[]{"Raster Layer", "Attract if >", "Repel if >", "Speed Modifier"}, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                switch (column) {
                    case 0:
                        return String.class;
                    case 1:
                        return Double.class;
                    case 2:
                        return Double.class;
                    case 3:
                        return Double.class;
                    default:
                        return Object.class;
                }
            }
        };

        responseTable = new JTable(responseTableModel);
        responseTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        responseTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        responseTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        responseTable.getColumnModel().getColumn(3).setPreferredWidth(100);

        // Set cell editors for response table
        responseTable.getColumnModel().getColumn(1).setCellEditor(numericEditor);
        responseTable.getColumnModel().getColumn(2).setCellEditor(numericEditor);

        // Special spinner editor for Speed Modifier column
        responseTable.getColumnModel().getColumn(3).setCellEditor(new SpinnerCellEditor());

        JPanel responsePanel = new JPanel(new BorderLayout());
        JButton addResponseBtn = new JButton("Add Layer Response");
        addResponseBtn.addActionListener(e -> addNewResponseRow());

        JPanel responseBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        responseBtnPanel.add(addResponseBtn);

        responsePanel.add(new JScrollPane(responseTable), BorderLayout.CENTER);
        responsePanel.add(responseBtnPanel, BorderLayout.SOUTH);
        tabs.addTab("Environmental Responses", responsePanel);

        centerPanel.add(tabs, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // Bottom buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnApply = new JButton("Save & Close");
        btnApply.addActionListener(e -> {
            saveCurrentToMemory();
            dispose();
        });

        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(e -> dispose());

        buttonPanel.add(btnCancel);
        buttonPanel.add(btnApply);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private TableCellEditor createNumericCellEditor() {
        return new DefaultCellEditor(new JTextField()) {
            @Override
            public Object getCellEditorValue() {
                try {
                    return Double.parseDouble(((JTextField) getComponent()).getText());
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }

            @Override
            public Component getTableCellEditorComponent(JTable table, Object value,
                    boolean isSelected, int row, int column) {
                Component component = super.getTableCellEditorComponent(table, value, isSelected, row, column);
                if (component instanceof JTextField) {
                    ((JTextField) component).setText(value != null ? value.toString() : "0.0");
                }
                return component;
            }
        };
    }

    // Custom cell editor for JSpinner
    private class SpinnerCellEditor extends AbstractCellEditor implements TableCellEditor {

        private JSpinner spinner = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 10.0, 0.1));

        @Override
        public Object getCellEditorValue() {
            return spinner.getValue();
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                boolean isSelected, int row, int column) {
            if (value instanceof Double) {
                spinner.setValue(value);
            } else if (value instanceof Number) {
                spinner.setValue(((Number) value).doubleValue());
            } else if (value != null) {
                try {
                    spinner.setValue(Double.parseDouble(value.toString()));
                } catch (NumberFormatException e) {
                    spinner.setValue(1.0);
                }
            } else {
                spinner.setValue(1.0);
            }
            return spinner;
        }
    }

    private void addNewResponseRow() {
        // Get available raster layers
        List<String> rasterLayers = new ArrayList<>();
        for (Layer layer : project.getLayers()) {
            if (layer instanceof RasterLayer && layer.isVisible()) {
                rasterLayers.add(layer.getName());
            }
        }

        if (rasterLayers.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No visible raster layers available.",
                    "No Layers", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Show dialog to select layer
        String selectedLayer = (String) JOptionPane.showInputDialog(
                this,
                "Select raster layer:",
                "Add Environmental Response",
                JOptionPane.QUESTION_MESSAGE,
                null,
                rasterLayers.toArray(),
                rasterLayers.get(0));

        if (selectedLayer != null) {
            // Check if layer already exists in table
            for (int i = 0; i < responseTableModel.getRowCount(); i++) {
                if (selectedLayer.equals(responseTableModel.getValueAt(i, 0))) {
                    JOptionPane.showMessageDialog(this,
                            "Layer already has a response entry.",
                            "Duplicate", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            responseTableModel.addRow(new Object[]{selectedLayer, 0.0, 0.0, 1.0});
        }
    }

    private void refreshRuleList() {
        ruleListModel.clear();
        for (int i = 0; i < agentLayer.getRules().size(); i++) {
            ruleListModel.addElement("Rule #" + (i + 1));
        }
    }

    private void loadRuleData(int index) {
        if (index < 0 || index >= agentLayer.getRules().size()) {
            return;
        }

        AgentRule rule = agentLayer.getRules().get(index);

        // Load base speed
        speedSpinner.setValue(rule.getMaxSpeed());

        // Load movement/terrain table
        movementTableModel.setRowCount(0);
        if (rule.getBehaviorMap().isEmpty()) {
            // Add default terrains
            movementTableModel.addRow(new Object[]{"land", true});
            movementTableModel.addRow(new Object[]{"water", false});
            movementTableModel.addRow(new Object[]{"forest", true});
            movementTableModel.addRow(new Object[]{"urban", true});
            movementTableModel.addRow(new Object[]{"empty", false});
        } else {
            rule.getBehaviorMap().forEach((terrain, allowed)
                    -> movementTableModel.addRow(new Object[]{terrain, allowed}));
        }

        // Load environmental risks table
        rasterTableModel.setRowCount(0);
        project.getLayers().stream()
                .filter(l -> l instanceof RasterLayer && l.isVisible())
                .forEach(rl -> {
                    Double lethalMax = rule.getLethalMaxThresholds().getOrDefault(rl.getName(), 100.0);
                    Double lethalMin = rule.getLethalMinThresholds().getOrDefault(rl.getName(), -100.0);
                    rasterTableModel.addRow(new Object[]{rl.getName(), lethalMax, lethalMin});
                });

        // Load environmental responses table
        responseTableModel.setRowCount(0);
        project.getLayers().stream()
                .filter(l -> l instanceof RasterLayer && l.isVisible())
                .forEach(rl -> {
                    Double attractThreshold = rule.getAttractantThresholds().getOrDefault(rl.getName(), 0.0);
                    Double repelThreshold = rule.getRepellentThresholds().getOrDefault(rl.getName(), 0.0);
                    Double speedModifier = rule.getSpeedModifiers().getOrDefault(rl.getName(), 1.0);
                    responseTableModel.addRow(new Object[]{
                        rl.getName(),
                        attractThreshold,
                        repelThreshold,
                        speedModifier
                    });
                });
    }

    private void saveCurrentToMemory() {
        if (selectedRuleIndex < 0 || selectedRuleIndex >= agentLayer.getRules().size()) {
            return;
        }

        stopEditing();
        AgentRule rule = agentLayer.getRules().get(selectedRuleIndex);

        // Save base speed
        double speed = ((Number) speedSpinner.getValue()).doubleValue();
        rule.setMaxSpeed(speed);
        agentLayer.setBaseSpeed(speed);

        // Save movement/terrain data
        rule.getBehaviorMap().clear();
        for (int i = 0; i < movementTableModel.getRowCount(); i++) {
            String terrain = (String) movementTableModel.getValueAt(i, 0);
            Boolean allowed = (Boolean) movementTableModel.getValueAt(i, 1);
            if (terrain != null && allowed != null) {
                rule.setBehavior(terrain.trim().toLowerCase(), allowed);
            }
        }

        // Save environmental risks
        rule.getLethalMaxThresholds().clear();
        rule.getLethalMinThresholds().clear();
        for (int i = 0; i < rasterTableModel.getRowCount(); i++) {
            String layerName = (String) rasterTableModel.getValueAt(i, 0);
            try {
                Object maxVal = rasterTableModel.getValueAt(i, 1);
                Object minVal = rasterTableModel.getValueAt(i, 2);

                if (layerName != null && maxVal != null) {
                    rule.getLethalMaxThresholds().put(layerName,
                            Double.parseDouble(maxVal.toString()));
                }
                if (layerName != null && minVal != null) {
                    rule.getLethalMinThresholds().put(layerName,
                            Double.parseDouble(minVal.toString()));
                }
            } catch (NumberFormatException ignored) {
                // Skip invalid entries
            }
        }

        // Save environmental responses
        rule.getAttractantThresholds().clear();
        rule.getRepellentThresholds().clear();
        rule.getSpeedModifiers().clear();

        for (int i = 0; i < responseTableModel.getRowCount(); i++) {
            String layerName = (String) responseTableModel.getValueAt(i, 0);
            if (layerName != null) {
                try {
                    Object attractVal = responseTableModel.getValueAt(i, 1);
                    Object repelVal = responseTableModel.getValueAt(i, 2);
                    Object speedModVal = responseTableModel.getValueAt(i, 3);

                    if (attractVal != null) {
                        rule.getAttractantThresholds().put(layerName,
                                Double.parseDouble(attractVal.toString()));
                    }
                    if (repelVal != null) {
                        rule.getRepellentThresholds().put(layerName,
                                Double.parseDouble(repelVal.toString()));
                    }
                    if (speedModVal != null) {
                        rule.getSpeedModifiers().put(layerName,
                                Double.parseDouble(speedModVal.toString()));
                    }
                } catch (NumberFormatException ignored) {
                    // Skip invalid entries
                }
            }
        }
    }

    private void stopEditing() {
        try {
            speedSpinner.commitEdit();
        } catch (Exception ignored) {
        }

        if (movementTable.isEditing()) {
            movementTable.getCellEditor().stopCellEditing();
        }
        if (rasterTable.isEditing()) {
            rasterTable.getCellEditor().stopCellEditing();
        }
        if (responseTable.isEditing()) {
            responseTable.getCellEditor().stopCellEditing();
        }
    }

    public double getBaseSpeed() {
        return ((Number) speedSpinner.getValue()).doubleValue();
    }
}
