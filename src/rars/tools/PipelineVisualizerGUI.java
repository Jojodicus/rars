/*
Copyright (c) 2024, Johannes Dittrich, Friedrich Alexander University Erlangen, Germany

Developed by Johannes Dittrich (johannes.dittrich(@)fau.de)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */
package rars.tools;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

public class PipelineVisualizerGUI {
    private final int STAGES;
    private static final String MEASURE_START = "PIPELINE_MEASURE_START";
    private static final String MEASURE_END = "PIPELINE_MEASURE_END";
    private static final String CONTROL_HAZARD_LABEL = " \u2BAB"; // arrow
    private static final String DATA_HAZARD_LABEL = " \u26A0\uFE0F"; // warning sign

    // row and column mappings for cell coloring
    private ArrayList<Map<Integer, Color>> colors;

    private JPanel panel;
    private JTable pipeline;
    private DefaultTableModel model;
    private JLabel speedupLabel;
    private JFrame helpFrame;
    private JLabel quickstart;

    public PipelineVisualizerGUI(int stages) {
        STAGES = stages;
        colors = new ArrayList<>(STAGES+1);
    }

    protected DefaultTableModel getModel() {
        return model;
    }

    protected JPanel getPanel() {
        return panel;
    }

    protected ArrayList<Map<Integer, Color>> getColors() {
        return colors;
    }

    protected JComponent buildMainDisplayArea() {
        for (int i = 0; i < STAGES+1; i++) {
            colors.add(new HashMap<>());
        }

        panel = new JPanel(new BorderLayout());

        quickstart = new JLabel("<html>To get started, connect this tool to your program, then run your program as normal.<br/>Click the help button below for further information.</html>");
        panel.add(quickstart, BorderLayout.NORTH);

        model = new DefaultTableModel();
        model.addColumn("CYCLE");
        model.addColumn("IF");
        model.addColumn("ID/OF");
        model.addColumn("EX");
        model.addColumn("MEM");
        model.addColumn("WB");

        // i have no idea what half of these options do
        pipeline = new JTable(model);
        pipeline.setShowGrid(true);
        pipeline.setGridColor(Color.BLACK);
        pipeline.setRowHeight(20);
        pipeline.setRowSelectionAllowed(false);
        pipeline.setCellSelectionEnabled(true);
        pipeline.setFillsViewportHeight(true);
        pipeline.setDefaultEditor(Object.class, null);

        // custom renderer for coloring cells
        pipeline.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                Color color = colors.get(column).get(row);

                if (color != null) {
                    c.setBackground(color);

                    // this has issues with green
                    // calculate (weighted) luminance
                    // int luminance = (3*color.getRed() + 4*color.getGreen() + color.getBlue()) >> 3;

                    // if color is dark, make text white
                    if (Math.max(color.getRed(), Math.max(color.getGreen(), color.getBlue())) < 128) {
                        c.setForeground(Color.WHITE);
                    } else {
                        c.setForeground(Color.BLACK);
                    }
                } else {
                    c.setBackground(Color.WHITE);
                    c.setForeground(Color.BLACK);
                }

                return c;
            }
        });

        // scroll to bottom
        pipeline.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                int lastIndex =pipeline.getCellRect(pipeline.getRowCount()-1, 0, false).y;
                pipeline.changeSelection(lastIndex, 0,false,false);
            }
        });

        JScrollPane scrollPane = new JScrollPane(pipeline);
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());

        speedupLabel = new JLabel();
        updateSpeedupText(0, 1, 1);
        bottomPanel.add(speedupLabel, BorderLayout.WEST);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    protected JComponent getHelpComponent() {
        String helpContent =
            "VAPOR\n\n" +
            "This tool visualizes the pipeline of a RISC-V processor.\n" +
            "It is modeled after the default 5-stage pipeline with the following stages:\n" +
            "IF: Instruction Fetch\n" +
            "ID/OF: Instruction Decode and Operand Fetch\n" +
            "EX: Execute\n" +
            "MEM: Memory Access\n" +
            "WB: Write Back\n\n" +
            "To use, connect the simulator to the program via the 'Connect to Program' button, then, you can step through the program as usual.\n" +
            "The pipeline and speedup will be updated automatically.\n" +
            "Even backstepping is supported, although due to a faulty implementation on the rars-side, one has to be careful with branches.\n\n" +
            "Each instruction in the pipeline corresponds to a non-pseudo instruction.\n" +
            "The number after each instruction shows the line in the original source code\n\n" +
            "The pipeline is colored according to the following scheme:\n" +
            "YELLOW + " + CONTROL_HAZARD_LABEL + ": control hazard\n" +
            "CYAN + " + DATA_HAZARD_LABEL + ": data hazard\n" +
            "GREEN + both labels: both control and data hazard\n\n" +
            "One can also instruct the simulator to only measure within a certain range of instructions.\n" +
            "To do this, add lines containing the following identifiers to the source code (for example in a comment):\n" +
            MEASURE_START + ": start measuring from this instruction\n" +
            MEASURE_END + ": stop measuring after this instruction\n" +
            "The identifiers are case-sensitive and include the lines they are on in their range.\n" +
            "During execution, when the start of such a region is reached, the cycle will be highlighted in DARK GRAY.";

        // help frame
        helpFrame = new JFrame("Help");
        helpFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        helpFrame.setLocationRelativeTo(null);

        // close button
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> {
            helpFrame.setVisible(false);
        });

        // text pane
        JTextPane helpTextPane = new JTextPane();
        helpTextPane.setEditable(false);

        // format text
        StyledDocument doc = helpTextPane.getStyledDocument();
        try {
            doc.insertString(0, helpContent, null);

            // VAPOR title
            SimpleAttributeSet title = new SimpleAttributeSet();
            StyleConstants.setFontSize(title, 20);
            StyleConstants.setBold(title, true);
            StyleConstants.setAlignment(title, StyleConstants.ALIGN_CENTER);
            doc.setParagraphAttributes(0, 5, title, false);

            // pipeline stages bold
            SimpleAttributeSet bold = new SimpleAttributeSet();
            StyleConstants.setBold(bold, true);
            doc.setCharacterAttributes(helpContent.indexOf("IF"), 3, bold, false);
            doc.setCharacterAttributes(helpContent.indexOf("ID/OF"), 6, bold, false);
            doc.setCharacterAttributes(helpContent.indexOf("EX"), 3, bold, false);
            doc.setCharacterAttributes(helpContent.indexOf("MEM"), 4, bold, false);
            doc.setCharacterAttributes(helpContent.indexOf("WB"), 3, bold, false);

            // connect to program bold
            doc.setCharacterAttributes(helpContent.indexOf("Connect to Program"), 19, bold, false);

            // measure start/end bold
            doc.setCharacterAttributes(helpContent.indexOf(MEASURE_START), MEASURE_START.length(), bold, false);
            doc.setCharacterAttributes(helpContent.indexOf(MEASURE_END), MEASURE_END.length(), bold, false);

            // hazard labels bold and colored

            // control hazard
            SimpleAttributeSet controlHazard = new SimpleAttributeSet();
            StyleConstants.setBold(controlHazard, true);
            StyleConstants.setBackground(controlHazard, Color.YELLOW);
            doc.setCharacterAttributes(helpContent.indexOf("YELLOW"), 11, controlHazard, false);

            // data hazard
            SimpleAttributeSet dataHazard = new SimpleAttributeSet();
            StyleConstants.setBold(dataHazard, true);
            StyleConstants.setBackground(dataHazard, Color.CYAN);
            doc.setCharacterAttributes(helpContent.indexOf("CYAN"), 9, dataHazard, false);

            // both hazards
            SimpleAttributeSet bothHazard = new SimpleAttributeSet();
            StyleConstants.setBold(bothHazard, true);
            StyleConstants.setBackground(bothHazard, Color.GREEN);
            doc.setCharacterAttributes(helpContent.indexOf("GREEN"), 19, bothHazard, false);

            // measuring ranges colored
            SimpleAttributeSet measureRange = new SimpleAttributeSet();
            StyleConstants.setBold(measureRange, true);
            StyleConstants.setBackground(measureRange, Color.DARK_GRAY);
            StyleConstants.setForeground(measureRange, Color.WHITE);
            doc.setCharacterAttributes(helpContent.indexOf("DARK GRAY"), 9, measureRange, false);
        } catch (BadLocationException e1) {
            e1.printStackTrace();
        }

        // helpTextPane.setContentType("text/html");
        // helpTextPane.setText(helpContentLabel);

        // scroll pane
        JScrollPane scrollPane = new JScrollPane(helpTextPane);
        scrollPane.setPreferredSize(new Dimension(600, 400));
        SwingUtilities.invokeLater(() -> {
            scrollPane.getVerticalScrollBar().setValue(0);
        });

        // help panel
        JPanel helpPanel = new JPanel(new BorderLayout());
        helpPanel.add(scrollPane, BorderLayout.CENTER);

        // south panel
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(closeButton, BorderLayout.EAST);
        helpPanel.add(southPanel, BorderLayout.SOUTH);

        // help frame
        helpFrame.add(helpPanel);
        helpFrame.pack();

        // generate help button for parent
        JButton helpButton = new JButton("Help");
        helpButton.addActionListener(e -> {
            helpFrame.setVisible(true);
        });
        return helpButton;
    }

    protected void updateSpeedupText(int instructionsExecuted, double speedup, double speedupWithoutHazards) {
        // assume 1 cycle per stage with pipeline, 5 cycles per stage without (i e all stalls)
        StringBuilder sb = new StringBuilder();
        sb.append("<html>");

        sb.append("Instructions executed: ");
        sb.append(instructionsExecuted);
        sb.append("<br/>");

        sb.append("Speedup: ");
        sb.append(String.format("%.2f", speedup));
        sb.append("<br/>");

        sb.append("Speedup without hazards: ");
        sb.append(String.format("%.2f", speedupWithoutHazards));

        sb.append("</html>");

        speedupLabel.setText(sb.toString());
    }
}
