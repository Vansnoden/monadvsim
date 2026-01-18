package com.monadvsim.app.views;

import com.monadvsim.app.models.AgentLayer;
import com.monadvsim.app.models.AgentRule;
import com.monadvsim.app.models.Project;
import com.monadvsim.app.models.RasterLayer;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

public class DialogRuleEditor extends JDialog {
    private final AgentLayer agentLayer;
    private final Project project;
    
    private DefaultTableModel movementTableModel;
    private DefaultTableModel rasterTableModel;
    private JSpinner speedSpinner;
    
    private DefaultListModel<String> ruleListModel;
    private JList<String> ruleList;
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

        setSize(750, 550);
        setLocationRelativeTo(parent);
    }

    private void initComponents() {
        setLayout(new BorderLayout(10, 10));

        // --- LEFT PANEL: Rule Stack ---
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Rules Stack"));
        leftPanel.setPreferredSize(new Dimension(150, 0));
        
        ruleListModel = new DefaultListModel<>();
        ruleList = new JList<>(ruleListModel);
        ruleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        ruleList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                saveCurrentToMemory(); // Save before switching
                int idx = ruleList.getSelectedIndex();
                if (idx >= 0) {
                    selectedRuleIndex = idx;
                    loadRuleData(idx);
                }
            }
        });

        JPanel ruleButtons = new JPanel(new GridLayout(1, 2));
        JButton btnAdd = new JButton("+");
        JButton btnDel = new JButton("-");
        
        btnAdd.addActionListener(e -> {
            agentLayer.addRule(new AgentRule());
            refreshRuleList();
            ruleList.setSelectedIndex(agentLayer.getRules().size() - 1);
        });
        
        btnDel.addActionListener(e -> {
            int idx = ruleList.getSelectedIndex();
            if (idx >= 0 && agentLayer.getRules().size() > 1) {
                agentLayer.getRules().remove(idx);
                selectedRuleIndex = 0;
                refreshRuleList();
                ruleList.setSelectedIndex(0);
            }
        });

        ruleButtons.add(btnAdd);
        ruleButtons.add(btnDel);
        leftPanel.add(new JScrollPane(ruleList), BorderLayout.CENTER);
        leftPanel.add(ruleButtons, BorderLayout.SOUTH);
        add(leftPanel, BorderLayout.WEST);

        // --- CENTER PANEL: Tabbed Configuration ---
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        
        JPanel topSettings = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topSettings.add(new JLabel("Base Speed:"));
        speedSpinner = new JSpinner(new SpinnerNumberModel(0.05, 0.0, 1000.0, 0.01));
        topSettings.add(speedSpinner);
        centerPanel.add(topSettings, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        
        // Tab A: Terrain Traversability
        movementTableModel = new DefaultTableModel(new Object[]{"Terrain Type", "Traversable"}, 0) {
            @Override public Class<?> getColumnClass(int col) { return col == 1 ? Boolean.class : String.class; }
        };
        tabs.addTab("Movement/Terrain", new JScrollPane(new JTable(movementTableModel)));

        // Tab B: Raster Thresholds
        rasterTableModel = new DefaultTableModel(new Object[]{"Raster Layer", "Lethal if Value >"}, 0);
        tabs.addTab("Environmental Risks", new JScrollPane(new JTable(rasterTableModel)));

        centerPanel.add(tabs, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        // --- SOUTH PANEL: Actions ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnApply = new JButton("Apply All Rules");
        btnApply.addActionListener(e -> {
            saveCurrentToMemory();
            dispose();
        });
        buttonPanel.add(btnApply);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void refreshRuleList() {
        ruleListModel.clear();
        for (int i = 0; i < agentLayer.getRules().size(); i++) {
            ruleListModel.addElement("Rule #" + (i + 1));
        }
    }

    private void loadRuleData(int index) {
        if (index < 0 || index >= agentLayer.getRules().size()) return;
        AgentRule rule = agentLayer.getRules().get(index);
        
        speedSpinner.setValue(rule.getMaxSpeed());

        // Load Movement Table
        movementTableModel.setRowCount(0);
        // Default types if nothing is set
        if (rule.getBehaviorMap().isEmpty()) {
            movementTableModel.addRow(new Object[]{"land", true});
            movementTableModel.addRow(new Object[]{"water", false});
            movementTableModel.addRow(new Object[]{"empty", false});
        } else {
            for (Map.Entry<String, Boolean> entry : rule.getBehaviorMap().entrySet()) {
                movementTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
            }
        }

        // Load Raster Table
        rasterTableModel.setRowCount(0);
        project.getLayers().stream()
            .filter(l -> l instanceof RasterLayer)
            .forEach(rl -> {
                Double threshold = rule.getLethalMaxThresholds().getOrDefault(rl.getName(), 100.0);
                rasterTableModel.addRow(new Object[]{rl.getName(), threshold});
            });
    }

    private void saveCurrentToMemory() {
        if (selectedRuleIndex < 0 || selectedRuleIndex >= agentLayer.getRules().size()) return;
        AgentRule rule = agentLayer.getRules().get(selectedRuleIndex);
        
        rule.setMaxSpeed((Double) speedSpinner.getValue());

        // Save Terrain
        for (int i = 0; i < movementTableModel.getRowCount(); i++) {
            String type = (String) movementTableModel.getValueAt(i, 0);
            Boolean allowed = (Boolean) movementTableModel.getValueAt(i, 1);
            rule.setBehavior(type, allowed != null && allowed);
        }

        // Save Rasters
        for (int i = 0; i < rasterTableModel.getRowCount(); i++) {
            String layerName = (String) rasterTableModel.getValueAt(i, 0);
            Object val = rasterTableModel.getValueAt(i, 1);
            try {
                double dVal = Double.parseDouble(val.toString());
                rule.getLethalMaxThresholds().put(layerName, dVal);
            } catch (Exception ignored) {}
        }
    }
}
