package com.monadvsim.app.views;

import com.monadvsim.app.models.Layer;
import com.monadvsim.app.models.Project;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.datatransfer.*;
import java.util.List;
import java.util.function.Supplier;

public class LayerTransferHandler extends TransferHandler {

    private final Runnable refreshCallback;
    private DefaultMutableTreeNode draggedNode;
    private final Supplier<Project> projectSupplier;

    public LayerTransferHandler(Supplier<Project> projectSupplier, Runnable refreshCallback) {
        this.projectSupplier = projectSupplier;
        this.refreshCallback = refreshCallback;
    }

    @Override
    public int getSourceActions(JComponent c) {
        return MOVE;
    }

    @Override
    protected Transferable createTransferable(JComponent c) {
        JTree tree = (JTree) c;
        draggedNode = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        // Ensure we are dragging a Layer, not the Root
        if (draggedNode == null || !(draggedNode.getUserObject() instanceof Layer)) {
            return null;
        }
        return new StringSelection(draggedNode.toString());
    }

    @Override
    public boolean canImport(TransferSupport support) {
        return support.isDataFlavorSupported(DataFlavor.stringFlavor);
    }

    @Override
    public boolean importData(TransferSupport support) {
        Project currentProject = projectSupplier.get();

        if (!canImport(support)) {
            return false;
        }

        JTree tree = (JTree) support.getComponent();
        JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
        int targetIndex = dl.getChildIndex();

        List<Layer> layers = currentProject.getLayers();
        if (targetIndex < 0) {
            targetIndex = layers.size();
        }

        if (draggedNode != null) {
            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) draggedNode.getParent();
            int sourceIndex = parent.getIndex(draggedNode);

            if (sourceIndex == targetIndex || sourceIndex == targetIndex - 1) {
                draggedNode = null;
                return true;
            }

            Layer movedLayer = layers.remove(sourceIndex);

            if (targetIndex > sourceIndex) {
                targetIndex--;
            }

            if (targetIndex < 0) {
                targetIndex = 0;
            }
            if (targetIndex > layers.size()) {
                targetIndex = layers.size();
            }

            layers.add(targetIndex, movedLayer);

            tree.clearSelection();
            draggedNode = null;

            SwingUtilities.invokeLater(() -> {
                refreshCallback.run();
            });
            return true;
        }
        return false;
    }

}
