package com.monadvsim.app;

import com.monadvsim.app.models.Project;
import com.monadvsim.app.views.MainWindow;
import com.monadvsim.app.controllers.MainController;

/**
 * Entry point
 */
public class App {

    public static void main(String[] args) {
        Project project = new Project();
        MainWindow view = new MainWindow();
        MainController controller = new MainController(project, view);
    }
}
