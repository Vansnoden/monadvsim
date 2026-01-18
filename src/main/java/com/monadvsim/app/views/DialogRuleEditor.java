package com.monadvsim.app.views;

import com.monadvsim.app.models.AgentLayer;
import com.monadvsim.app.models.AgentRule;
import com.monadvsim.app.models.Project;
import com.monadvsim.app.models.RasterLayer;
import javax.swing.JDialog;
import javax.swing.table.DefaultTableModel;
import java.util.Map;
import javax.swing.JTabbedPane;
import javax.swing.JPanel;
import javax.swing.JFrame;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JButton;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SpinnerNumberModel;


public class DialogRuleEditor extends JDialog {
  private final AgentLayer agentLayer;
  private final Project project;
  private DefaultTableModel movementTableModel;
  private DefaultTableModel rasterTableModel;
  private JSpinner speedSpinner;

  public DialogRuleEditor(JFrame parent, AgentLayer layer, Project project) {
    super(parent, "Configure Rules: " + layer.getName(), true);
    this.agentLayer = layer;
    this.project = project;
    initComponents();
    loadData();
    setSize(500, 600);
    setLocationRelativeTo(parent);
  }

  private void initComponents() {
    setLayout(new BorderLayout(10, 10));
    // 1. General Settings (Speed)
    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    topPanel.add(new JLabel("Base Movement Speed:"));
    speedSpinner = new JSpinner(new SpinnerNumberModel(agentLayer.getRule().getMaxSpeed(), 0.001, 10.0, 0.01));
    topPanel.add(speedSpinner);
    add(topPanel, BorderLayout.NORTH);
    // 2. Tabbed Pane for different rule types
    JTabbedPane tabs = new JTabbedPane();
    // TAB A: Vector/Terrain Traversability
    movementTableModel = new DefaultTableModel(new Object[]{"Terrain Type", "Traversable (Move)"}, 0) {
        @Override public Class<?> getColumnClass(int col) { return col == 1 ? Boolean.class : String.class; }
    };
    tabs.addTab("Terrain/Obstacles", new JScrollPane(new JTable(movementTableModel)));
    // TAB B: Raster Thresholds (Lethality/Sensing)
    rasterTableModel = new DefaultTableModel(new Object[]{"Raster Layer", "Lethal if > Value"}, 0);
    tabs.addTab("Environment Risks", new JScrollPane(new JTable(rasterTableModel)));
    add(tabs, BorderLayout.CENTER);
    // 3. Action Buttons
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    JButton btnSave = new JButton("Apply Rules");
    btnSave.addActionListener(e -> saveAndClose());
    buttonPanel.add(btnSave);
    add(buttonPanel, BorderLayout.SOUTH);
  }

  private void loadData() {
    AgentRule rule = agentLayer.getRule();
    // Load Vector behaviors (Land/Water/etc)
    for (Map.Entry<String, Boolean> entry : rule.getBehaviorMap().entrySet()) {
      movementTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
    }
    // Populate Raster options from current project layers
    project.getLayers().stream()
    .filter(l -> l instanceof RasterLayer)
    .forEach(rl -> {
       rasterTableModel.addRow(new Object[]{rl.getName(), 100.0}); // Default high threshold
    });
  }

  private void saveAndClose() {
    AgentRule rule = agentLayer.getRule();
    rule.setMaxSpeed((Double) speedSpinner.getValue());
    // Update behaviors from table
    for (int i = 0; i < movementTableModel.getRowCount(); i++) {
      String type = (String) movementTableModel.getValueAt(i, 0);
      boolean allowed = (Boolean) movementTableModel.getValueAt(i, 1);
      rule.setBehavior(type, allowed);
    }
    // Note: You can expand this to save the raster lethality thresholds 
    // using the rule.setLethalMax() method we designed earlier.
    dispose();
  }
}
