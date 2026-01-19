package com.monadvsim.app.views;

import com.monadvsim.app.models.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;



public class DialogRuleEditor extends JDialog {

    private final AgentLayer agentLayer;
    private final Project project;
    private JTable movementTable, rasterTable;
    private DefaultTableModel movementTableModel, rasterTableModel;
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
        setSize(750, 550);
        setLocationRelativeTo(parent);
    }


    private void initComponents() {
        setLayout(new BorderLayout(10, 10));

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Rules Stack"));
        leftPanel.setPreferredSize(new Dimension(180, 0));
        ruleListModel = new DefaultListModel<>();
        ruleList = new JList<>(ruleListModel);
        ruleList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                saveCurrentToMemory();
                int idx = ruleList.getSelectedIndex();
                if (idx >= 0) { selectedRuleIndex = idx; loadRuleData(idx); }
            }
        });

        JPanel ruleButtons = new JPanel(new GridLayout(1, 2));
        JButton btnAdd = new JButton("Add"), btnDel = new JButton("Remove");
        btnAdd.addActionListener(e -> { 
          agentLayer.addRule(new AgentRule()); 
          refreshRuleList(); 
          ruleList.setSelectedIndex(agentLayer.getRules().size()-1); 
        });
        btnDel.addActionListener(e -> {
            int idx = ruleList.getSelectedIndex();
            if (idx >= 0 && agentLayer.getRules().size() > 1) {
                agentLayer.getRules().remove(idx);
                refreshRuleList(); ruleList.setSelectedIndex(0);
            }
        });
        ruleButtons.add(btnAdd); ruleButtons.add(btnDel);
        leftPanel.add(new JScrollPane(ruleList), BorderLayout.CENTER);
        leftPanel.add(ruleButtons, BorderLayout.SOUTH);
        add(leftPanel, BorderLayout.WEST);

        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        JPanel topSettings = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topSettings.add(new JLabel("Base Speed (units/tick):"));
        speedSpinner = new JSpinner(new SpinnerNumberModel(agentLayer.getBaseSpeed(), 
        0.0, 1000.0, 0.01));
        topSettings.add(speedSpinner);
        centerPanel.add(topSettings, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        movementTableModel = new DefaultTableModel(new Object[]{"Terrain Type", "Traversable"}, 0) {
            @Override 
            public Class<?> getColumnClass(int col) { 
              return col == 1 ? Boolean.class : String.class; 
            }
        };
        movementTable = new JTable(movementTableModel);
        tabs.addTab("Movement/Terrain", new JScrollPane(movementTable));

        rasterTableModel = new DefaultTableModel(new Object[]{"Raster Layer", "Lethal if Value >"},
        0);
        rasterTable = new JTable(rasterTableModel);
        tabs.addTab("Environmental Risks", new JScrollPane(rasterTable));
        centerPanel.add(tabs, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);
        JButton btnApply = new JButton("Save & Close");
        btnApply.addActionListener(e -> { saveCurrentToMemory(); dispose(); });
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT)); bp.add(btnApply);
        add(bp, BorderLayout.SOUTH);
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
        movementTableModel.setRowCount(0);
        if (rule.getBehaviorMap().isEmpty()) {
            movementTableModel.addRow(new Object[]{"land", true});
            movementTableModel.addRow(new Object[]{"water", false});
        } else {
            rule.getBehaviorMap().forEach((k, v) -> movementTableModel.addRow(new Object[]{k, v}));
        }
        rasterTableModel.setRowCount(0);
        project.getLayers().stream().filter(l -> l instanceof RasterLayer).forEach(rl -> 
            rasterTableModel.addRow(new Object[]{rl.getName(), 
            rule.getLethalMaxThresholds().getOrDefault(rl.getName(), 100.0)}));
    }


    private void saveCurrentToMemory() {
        if (selectedRuleIndex < 0 || selectedRuleIndex >= agentLayer.getRules().size()) return;
        stopEditing();
        AgentRule rule = agentLayer.getRules().get(selectedRuleIndex);
        double speed = ((Number) speedSpinner.getValue()).doubleValue();
        rule.setMaxSpeed(speed);
        agentLayer.setBaseSpeed(speed);

        for (int i = 0; i < movementTableModel.getRowCount(); i++)
            rule.setBehavior((String)movementTableModel.getValueAt(i,0), 
            (Boolean)movementTableModel.getValueAt(i,1));

        for (int i = 0; i < rasterTableModel.getRowCount(); i++) {
            try { rule.getLethalMaxThresholds().put((String)rasterTableModel.getValueAt(i,0), 
            Double.parseDouble(rasterTableModel.getValueAt(i,1).toString())); } 
            catch (Exception ignored) {}
        }
    }


    private void stopEditing() {
        try { speedSpinner.commitEdit(); } catch (Exception ignored) {}
        if (movementTable.isEditing()) movementTable.getCellEditor().stopCellEditing();
        if (rasterTable.isEditing()) rasterTable.getCellEditor().stopCellEditing();
    }
    

    public double getBaseSpeed() { 
      return ((Number) speedSpinner.getValue()).doubleValue(); 
    }
}
