package com.monadvsim.app.views;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import java.awt.Frame;
import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.SwingConstants;

public class DialogLoading extends JDialog {

    public DialogLoading(Frame parent, String message) {
        super(parent, "Please Wait", true);
        setLayout(new BorderLayout());
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        JLabel label = new JLabel(message);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        JProgressBar pb = new JProgressBar();
        pb.setIndeterminate(true);
        panel.add(label, BorderLayout.NORTH);
        panel.add(pb, BorderLayout.CENTER);
        add(panel);
        pack();
        setLocationRelativeTo(parent);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    }

}
