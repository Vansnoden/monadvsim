package com.monadvsim.app.controllers;

import com.monadvsim.app.models.Project;
import com.monadvsim.app.models.ProjectPersistenceService;
import com.monadvsim.app.models.RecentProjectsService;
import com.monadvsim.app.models.VectorLayer;
import com.monadvsim.app.models.RasterLayer;
import com.monadvsim.app.models.AgentLayer;
import com.monadvsim.app.models.Layer;
import com.monadvsim.app.views.MainWindow;
import com.monadvsim.app.views.DialogNewProject;
import com.monadvsim.app.views.DialogNewLayer;
import com.monadvsim.app.views.DialogLoading;
import com.monadvsim.app.views.DialogProjectProperties;
import com.monadvsim.app.views.DialogLayerProperties;
import com.monadvsim.app.views.DialogRuleEditor;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.util.List;

import org.geotools.swing.tool.PanTool;
import org.geotools.swing.tool.ZoomInTool;
import org.geotools.swing.tool.ZoomOutTool;

public class MainController {

    private Project project = null;
    private MainWindow view = null;
    private ProjectPersistenceService projectPersistenceService = null;
    private final RecentProjectsService recentService = new RecentProjectsService();
    private boolean isBaking = false;
    private Timer simulationTimer;

    public MainController(Project project, MainWindow view) {
        this.project = project;
        this.view = view;
        this.projectPersistenceService = new ProjectPersistenceService();
        this.view.setStatusBarCRS(project.getCrsCode());
        this.initListeners();
        updateRecentMenu();
        if (project.getCrsCode() != null) {
            view.getSimulationCanvas().updateViewportCRS(project.getCrsCode());
        }
    }

    private void initListeners() {
        this.view.getBtnNewProject().addActionListener(l -> handleNewProject());
        this.view.getBtnAddLayer().addActionListener(l -> handleNewLayer());
        this.view.getBtnOpenProject().addActionListener(l -> handleOpenProjectFlow());
        this.view.getBtnSaveProject().addActionListener(l -> handleSaveProject());
        this.view.getBtnRunSim().addActionListener(l -> toggleSimulation(true));
        this.view.getBtnPauseSim().addActionListener(l -> toggleSimulation(false));
        this.initMapTools();
        this.handleLayerTreeMouse();
        this.initSimulationClock();
        this.handleViewPortCoordinatesUpdate();
    }

    private void handleViewPortCoordinatesUpdate() {
        this.view.getSimulationCanvas().addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (view.getSimulationCanvas().getMapContent() == null
                        || view.getSimulationCanvas().getMapContent().getViewport() == null) return;
                try {
                    AffineTransform screenToWorld = view.getSimulationCanvas().getScreenToWorldTransform();
                    if (screenToWorld != null) {
                        Point2D screenPt = e.getPoint();
                        Point2D worldPt = new Point2D.Double();
                        screenToWorld.transform(screenPt, worldPt);
                        view.getLblCoordinates().setText(
                                String.format("X: %.4f, Y: %.4f", worldPt.getX(), worldPt.getY())
                        );
                    }
                } catch (Exception ex) {
                    // Ignore transformation errors
                }
            }
        });
    }

    private void handleOpenProject(File file) {
        DialogLoading loading = new DialogLoading(view, "Opening Project...");
        SwingWorker<Project, Void> worker = new SwingWorker<>() {
            @Override
            protected Project doInBackground() throws Exception {
                return projectPersistenceService.loadProject(file);
            }

            @Override
            protected void done() {
                try {
                    project = get();
                    recentService.addProject(file);
                    updateRecentMenu();
                    view.getSimulationCanvas().updateViewportCRS(project.getCrsCode());
                    refreshUI();
                    view.setProjectNameInTree(project.getName());
                    view.setTitle("MonadVSIM - " + project.getName());
                    view.getSimulationCanvas().zoomToData();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(view, "Load failed: " + ex.getMessage());
                    ex.printStackTrace();
                } finally {
                    loading.dispose();
                }
            }
        };
        worker.execute();
    }

    private void handleOpenProjectFlow() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Monad Project", "mvsim"));
        if (chooser.showOpenDialog(view) == JFileChooser.APPROVE_OPTION) {
            handleOpenProject(chooser.getSelectedFile());
        }
    }

    private void handleNewProject() {
        DialogNewProject dialog = new DialogNewProject();
        dialog.getBtnSelectFolder().addActionListener(l -> {
            File selectedFolder = openFileChooser(dialog, JFileChooser.DIRECTORIES_ONLY, null);
            if (selectedFolder != null) dialog.getSelectFolderLabel().setText(selectedFolder.getAbsolutePath());
        });
        dialog.getBtnSave().addActionListener(l -> {
            try {
                String name = dialog.getProjectNameField().getText();
                File pFile = new File(dialog.getSelectFolderLabel().getText(), name + ".mvsim");
                project = new Project();
                project.setName(name);
                project.setCrs("EPSG:4326");
                project.setProjectFile(pFile);
                projectPersistenceService.saveProject(project, pFile);
                view.setTitle("MonadVSIM - " + name);
                refreshUI();
                dialog.dispose();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "New project creation failed: " + ex.getMessage());
            }
        });
        dialog.setVisible(true);
    }

    private void updateRecentMenu() {
        JMenu menu = view.getRecentProjectsMenu();
        if (menu == null) return;
        menu.removeAll();
        List<String> paths = recentService.getRecentProjects();
        for (String path : paths) {
            File file = new File(path);
            JMenuItem item = new JMenuItem(file.getName());
            item.setToolTipText(path);
            item.addActionListener(e -> handleOpenProject(file));
            menu.add(item);
        }
    }

    private void handleLayerTreeMouse() {
        this.view.getLayerTree().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                TreePath path = view.getLayerTree().getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                int row = view.getLayerTree().getRowForPath(path);
                if (node.getUserObject() instanceof Layer layer) {
                    Rectangle rect = view.getLayerTree().getRowBounds(row);
                    if (SwingUtilities.isLeftMouseButton(e) && e.getX() < rect.x + 25) {
                        toggleLayerVisibility(layer);
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        view.getLayerTree().setSelectionPath(path);
                        showTreeContextMenu(e, layer);
                    }
                } else if (SwingUtilities.isRightMouseButton(e) && row == 0) {
                    showProjectContextMenu(e);
                }
            }
        });
    }

    private void initMapTools() {
        this.view.getBtnZoomIn().addActionListener(e -> view.getSimulationCanvas().setActiveTool(new ZoomInTool()));
        this.view.getBtnZoomOut().addActionListener(e -> view.getSimulationCanvas().setActiveTool(new ZoomOutTool()));
        this.view.getBtnPanview().addActionListener(e -> view.getSimulationCanvas().setActiveTool(new PanTool()));
        this.view.getBtnFullview().addActionListener(e -> {
            view.getSimulationCanvas().setActiveTool(null);
            view.getSimulationCanvas().zoomToData();
        });
    }

    private void handleSaveProject() {
        try {
            if (project.getProjectFile() == null) {
                handleNewProject();
            } else {
                projectPersistenceService.saveProject(project, project.getProjectFile());
                JOptionPane.showMessageDialog(view, "Project Saved Successfully.");
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(view, "Save Failed: " + e.getMessage());
        }
    }

    private void handleNewLayer() {
        DialogNewLayer dialog = new DialogNewLayer(view);
        if (dialog.isSucceeded()) {
            if (dialog.getLayerType().equals("Agent Layer")) {
                handleCreateAgentLayer(dialog.getLayerName(), dialog.getPopulation(), dialog.isWrapAround());
            } else {
                handleCreateSpatialLayer(dialog.getLayerType(), dialog.getLayerName());
            }
        }
    }

    private File openFileChooser(JDialog parent, int mode, List<String> ext) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(mode);
        if (ext != null && !ext.isEmpty()) {
            FileNameExtensionFilter filter = new FileNameExtensionFilter("Supported Files", ext.toArray(new String[0]));
            chooser.setFileFilter(filter);
        }
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    private void refreshUI() {
        view.updateLayerTree(project.getLayers());
        view.getSimulationCanvas().updateLayers(project.getLayers(), project.getProjectFile());
    }

    private void toggleLayerVisibility(Layer layer) {
        layer.setVisible(!layer.isVisible());
        refreshUI();
    }

    private void handleCreateAgentLayer(String name, int population, boolean wrap) {
        AgentLayer al = new AgentLayer(name);
        al.setWrapAround(wrap);
        al.setPopulation(population, project);
        project.getLayers().add(al);
        refreshUI();
    }

    private void handleCreateSpatialLayer(String type, String name) {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(view) == JFileChooser.APPROVE_OPTION) {
            File layerFile = chooser.getSelectedFile();
            Layer newLayer = type.equals("Vector Layer") ?
                    new VectorLayer(name, layerFile.getAbsolutePath()) :
                    new RasterLayer(name, layerFile.getAbsolutePath());
            project.getLayers().add(newLayer);
            refreshUI();
        }
    }

    private void handleProjectProperties() {
        DialogProjectProperties dialog = new DialogProjectProperties(view, project.getName(), project.getCrsCode());
        dialog.getBtnApply().addActionListener(e -> {
            project.setName(dialog.getProjectName());
            project.setCrsCode(dialog.getSelectedCrs());
            view.getSimulationCanvas().updateViewportCRS(project.getCrsCode());
            refreshUI();
            dialog.dispose();
        });
        dialog.setVisible(true);
    }

    private void showProjectContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem prop = new JMenuItem("Project Properties...");
        prop.addActionListener(al -> handleProjectProperties());
        menu.add(prop);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void showTreeContextMenu(MouseEvent e, Layer layer) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem prop = new JMenuItem("Layer Properties...");
        prop.addActionListener(al -> handleLayerProperties(layer));
        menu.add(prop);
        if (layer instanceof AgentLayer al) {
            JMenuItem ruleItem = new JMenuItem("Configure Rules...");
            ruleItem.addActionListener(alEvent -> {
                DialogRuleEditor editor = new DialogRuleEditor(view, al, project);
                editor.setVisible(true);
            });
            menu.add(ruleItem);
        }
        menu.addSeparator();
        JMenuItem remove = new JMenuItem("Remove Layer");
        remove.addActionListener(al -> {
            project.getLayers().remove(layer);
            refreshUI();
        });
        menu.add(remove);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void handleLayerProperties(Layer layer) {
        DialogLayerProperties dialog = new DialogLayerProperties(view, layer);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            layer.setName(dialog.getLayerName());
            layer.setVisible(dialog.isVisible());
            layer.setCrsCode(dialog.getSelectedCrs());
            if (layer instanceof AgentLayer al) {
                al.setColorHex(dialog.getColorHex());
                al.setWrapAround(dialog.isWrap());
                al.setTrailsEnabled(dialog.isTrailsEnabled());
                if (al.getAgentsCount() != dialog.getPopulation()) {
                    al.setPopulation(dialog.getPopulation(), project);
                }
            } else if (layer instanceof VectorLayer vl) {
                vl.setColorHex(dialog.getColorHex());
            }
            layer.setOpacity(dialog.getOpacity());
            refreshUI();
        }
    }

    public void toggleSimulation(boolean run) {
        if (run) {
            if (project.getProjectFile() == null) {
                JOptionPane.showMessageDialog(view, "Please save the project before running simulation.");
                return;
            }
            ensureTerrainBaked(() -> {
                simulationTimer.start();
                view.getBtnRunSim().setEnabled(false);
                view.getBtnPauseSim().setEnabled(true);
            });
        } else {
            if (simulationTimer != null) simulationTimer.stop();
            view.getBtnRunSim().setEnabled(true);
            view.getBtnPauseSim().setEnabled(false);
        }
    }

    private void ensureTerrainBaked(Runnable callback) {
        AgentLayer al = findFirstAgentLayer();
        if (al == null) {
            JOptionPane.showMessageDialog(view, "No visible Agent Layer found.");
            return;
        }

        if (al.getTerrainCache() == null) {
            isBaking = true;
            view.getProgressBar().setVisible(true);
            view.getProgressBar().setStringPainted(true);

            new SwingWorker<Void, Integer>() {
                @Override
                protected Void doInBackground() throws Exception {
                    al.bakeTerrainCache(project, 1000, progress -> publish(progress));
                    return null;
                }

                @Override
                protected void process(List<Integer> chunks) {
                    int latest = chunks.get(chunks.size() - 1);
                    view.getProgressBar().setValue(latest);
                    view.getProgressBar().setString("Baking Terrain: " + latest + "%");
                }

                @Override
                protected void done() {
                    isBaking = false;
                    view.getProgressBar().setVisible(false);
                    callback.run();
                }
            }.execute();
        } else {
            callback.run();
        }
    }

    private AgentLayer findFirstAgentLayer() {
        return project.getLayers().stream()
                .filter(l -> l instanceof AgentLayer && l.isVisible())
                .map(l -> (AgentLayer) l)
                .findFirst()
                .orElse(null);
    }

    private void initSimulationClock() {
        // Smooth 60FPS target
        simulationTimer = new Timer(16, e -> {
            if (project == null || isBaking) return;
            for (Layer layer : project.getLayers()) {
                if (layer instanceof AgentLayer al && al.isVisible()) {
                    al.updateAll(project);
                }
            }
            view.getSimulationCanvas().repaint();
        });
    }
}
