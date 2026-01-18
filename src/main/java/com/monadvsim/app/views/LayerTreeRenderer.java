package com.monadvsim.app.views;


import com.monadvsim.app.models.VectorLayer;
import com.monadvsim.app.models.RasterLayer;
import com.monadvsim.app.models.AgentLayer;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.JCheckBox;
import java.awt.BorderLayout;
import javax.swing.ImageIcon;
import java.net.URL;
import javax.swing.UIManager;
import javax.swing.JPanel;
import javax.swing.JLabel;
import com.monadvsim.app.models.Layer;
import java.awt.Font;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.Component;
import javax.swing.JTree;


public class LayerTreeRenderer extends JPanel implements TreeCellRenderer {
  private final JCheckBox checkBox = new JCheckBox();
  private final JLabel label = new JLabel();
  
  public LayerTreeRenderer(){
    setLayout(new BorderLayout());
    setOpaque(false);
    checkBox.setOpaque(false);
    add(checkBox, BorderLayout.WEST);
    add(label, BorderLayout.CENTER);
  }
  
  @Override
  public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, 
    boolean expanded, boolean leaf, int row, boolean hasFocus) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
    Object userObject = node.getUserObject();
    // 1. Logic for Layers
    if (userObject instanceof Layer layer) {
      checkBox.setVisible(true);
      checkBox.setSelected(layer.isVisible());
      label.setText(layer.getName());
      label.setFont(tree.getFont());
      // Optional: Assign icons based on layer type for better User Experience
      if (layer instanceof VectorLayer) label.setIcon(loadIcon("vector_layer"));
      else if (layer instanceof RasterLayer) label.setIcon(loadIcon("raster_layer"));
      else if (layer instanceof AgentLayer) label.setIcon(loadIcon("agent_layer"));
      
    } else {
      // 2. Logic for the Root Node (Project Name)
      checkBox.setVisible(false);
      label.setIcon(loadIcon("folder"));
      label.setText(userObject != null ? userObject.toString() : "Project");
      label.setFont(tree.getFont().deriveFont(Font.BOLD));
    }
    // 3. Handle Selection Colors
    if (selected) {
      label.setForeground(tree.getSelectionModel().getSelectionPath() != null ? UIManager.getColor("Tree.selectionForeground") : tree.getForeground());
      this.setBackground(UIManager.getColor("Tree.selectionBackground"));
      this.setOpaque(true);
    } else {
      label.setForeground(tree.getForeground());
      this.setOpaque(false);
    }
    return this;
  }
  
  private ImageIcon loadIcon(String name){
    URL imageURL = getClass().getResource("/icons/" + name + ".png");
    return (imageURL != null)? new ImageIcon(imageURL) : null;
  }
  

}
