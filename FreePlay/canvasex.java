import javax.swing.*;
import javax.swing.plaf.basic.BasicSliderUI;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.*;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;

/**
 * canvasex — enhanced: serialisation + autosave + Ctrl+S
 */
public class canvasex extends JFrame {

    // ====== Canvas / Shapes
    interface DrawingShape { void draw(Graphics2D g); }

    // Make shapes Serializable so we can persist them
    static class LineShape implements DrawingShape, Serializable {
        private static final long serialVersionUID = 1L;
        Point a, b; Color color; int size;
        LineShape(Point a, Point b, Color c, int s) { this.a=a; this.b=b; this.color=c; this.size=s; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(size, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(a.x, a.y, b.x, b.y);
        }
    }

    static class RectShape implements DrawingShape, Serializable {
        private static final long serialVersionUID = 1L;
        Point a, b; Color color; int size;
        RectShape(Point a, Point b, Color c, int s) { this.a=a; this.b=b; this.color=c; this.size=s; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(size));
            g.drawRect(Math.min(a.x,b.x), Math.min(a.y,b.y), Math.abs(a.x-b.x), Math.abs(a.y-b.y));
        }
    }

    static class OvalShape implements DrawingShape, Serializable {
        private static final long serialVersionUID = 1L;
        Point a, b; Color color; int size;
        OvalShape(Point a, Point b, Color c, int s) { this.a=a; this.b=b; this.color=c; this.size=s; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(size));
            g.drawOval(Math.min(a.x,b.x), Math.min(a.y,b.y), Math.abs(a.x-b.x), Math.abs(a.y-b.y));
        }
    }

    static class TextShape implements DrawingShape, Serializable {
        private static final long serialVersionUID = 1L;
        String text; Point pos; Color color; Font font;
        TextShape(String t, Point p, Color c, Font f) { this.text=t; this.pos=p; this.color=c; this.font=f; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(color);
            g.setFont(font);
            g.drawString(text, pos.x, pos.y);
        }
    }

    // ===== soft shadow helper (simple, high-quality look without heavy blur ops)
    private static void paintSoftShadow(Graphics2D g2, Shape s, int blurRadius, float baseAlpha) {
        if (blurRadius <= 0) return;
        // Save transform & composite
        AffineTransform oldT = g2.getTransform();
        Composite oldC = g2.getComposite();

        // stacked translucent fills slightly offset to simulate blur
        for (int i = blurRadius; i >= 1; i--) {
            float a = baseAlpha * (i / (float)(blurRadius + 1)) * 0.9f;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, a))));
            AffineTransform t = AffineTransform.getTranslateInstance(0, i/2.0);
            g2.transform(t);
            g2.fill(s);
            g2.setTransform(oldT);
        }
        g2.setComposite(oldC);
    }

    // ===== ProjectData for persistence
    static class ProjectData implements Serializable {
        private static final long serialVersionUID = 1L;
        List<DrawingShape> shapes;
        double scale;
        double offX, offY;
        int penSize;
        Color drawColor;
        Color textColor;
        int fontSize;
        String fontFamily;
        int fontStyle;

        ProjectData(List<DrawingShape> shapes, double scale, double offX, double offY,
                    int penSize, Color drawColor, Color textColor,
                    int fontSize, String fontFamily, int fontStyle) {
            this.shapes = new ArrayList<>(shapes);
            this.scale = scale; this.offX = offX; this.offY = offY;
            this.penSize = penSize;
            this.drawColor = drawColor;
            this.textColor = textColor;
            this.fontSize = fontSize;
            this.fontFamily = fontFamily;
            this.fontStyle = fontStyle;
        }
    }

    // =================== CANVAS VIEW ===================
    static class CanvasView extends JComponent {
        // Daten
        List<DrawingShape> shapes = new ArrayList<>();

        // View-Transform
        double scale = 1.0;
        double offX = 0, offY = 0;

        // Interaktion
        Point lastWorld = null;
        Point dragStartWorld = null;
        Point dragNowWorld = null;
        boolean panning = false;
        int panLastX, panLastY;

        // Tools
        enum Mode { PEN, LINE, RECT, OVAL, TEXT }
        Mode mode = Mode.PEN;

        // Stil
        Color color = Color.BLACK;     // allgemeine Zeichenfarbe
        Color textColor = Color.BLACK; // Text-spezifische Farbe (für Text-Panel)
        int penSize = 4;
        String fontFamily = "Arial";
        int fontSize = 24;
        int fontStyle = Font.PLAIN;

        // Text-Tippen
        boolean typing = false;
        Point textStartWorld = null;
        StringBuilder textBuffer = new StringBuilder();

        // NEU: Auswahl und Drag für Shapes/Text
        DrawingShape selectedShape = null;
        Point dragOffset = null;

        CanvasView() {
            setOpaque(true);
            setBackground(Color.WHITE);
            setFocusable(true);

            addMouseWheelListener(this::onWheel);

            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (SwingUtilities.isRightMouseButton(e)) {
                        panning = true; panLastX = e.getX(); panLastY = e.getY();
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        return;
                    }
                    Point w = toWorld(e.getPoint());

                    // === Text-Tippen: Klick außerhalb = übernehmen ===
                    if (typing) {
                        Rectangle textBounds = getTextBounds(textStartWorld, textBuffer.toString());
                        if (textBounds == null || !textBounds.contains(w)) {
                            drawText(textBuffer.toString(), textStartWorld);
                            typing = false;
                            textStartWorld = null;
                            textBuffer.setLength(0);
                            repaint();
                            // Weiter: evtl. Auswahl prüfen
                        } else {
                            // Klick im Textfeld: weiter tippen
                            return;
                        }
                    }

                    // === Auswahl prüfen ===
                    selectedShape = null;
                    for (int i = shapes.size() - 1; i >= 0; i--) {
                        DrawingShape s = shapes.get(i);
                        if (shapeContains(s, w)) {
                            selectedShape = s;
                            if (s instanceof LineShape l) {
                                dragOffset = new Point(w.x - l.a.x, w.y - l.a.y);
                            } else if (s instanceof RectShape r) {
                                dragOffset = new Point(w.x - r.a.x, w.y - r.a.y);
                            } else if (s instanceof OvalShape o) {
                                dragOffset = new Point(w.x - o.a.x, w.y - o.a.y);
                            } else if (s instanceof TextShape t) {
                                dragOffset = new Point(w.x - t.pos.x, w.y - t.pos.y);
                            }
                            repaint();
                            return;
                        }
                    }

                    // === Wenn keine Auswahl und Textmodus: neues Textfeld ===
                    if (selectedShape == null && mode == Mode.TEXT && !typing) {
                        typing = true;
                        textStartWorld = w;
                        textBuffer.setLength(0);
                        repaint();
                        return;
                    }

                    // === Zeichnen vorbereiten ===
                    dragStartWorld = w;
                    lastWorld = w;
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        panning = false; setCursor(Cursor.getDefaultCursor());
                        return;
                    }
                    if (selectedShape != null) {
                        dragOffset = null;
                        return;
                    }
                    if (dragStartWorld == null) return;
                    Point w = toWorld(e.getPoint());
                    commitShape(dragStartWorld, w);
                    dragStartWorld = null;
                    dragNowWorld = null;
                    repaint();
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    if (panning) {
                        offX += e.getX() - panLastX;
                        offY += e.getY() - panLastY;
                        panLastX = e.getX(); panLastY = e.getY();
                        repaint();
                        return;
                    }
                    Point w = toWorld(e.getPoint());
                    // === Drag für Auswahl ===
                    if (selectedShape != null && dragOffset != null) {
                        if (selectedShape instanceof LineShape l) {
                            int dx = w.x - dragOffset.x - l.a.x;
                            int dy = w.y - dragOffset.y - l.a.y;
                            l.a.translate(dx, dy);
                            l.b.translate(dx, dy);
                        } else if (selectedShape instanceof RectShape r) {
                            int dx = w.x - dragOffset.x - r.a.x;
                            int dy = w.y - dragOffset.y - r.a.y;
                            r.a.translate(dx, dy);
                            r.b.translate(dx, dy);
                        } else if (selectedShape instanceof OvalShape o) {
                            int dx = w.x - dragOffset.x - o.a.x;
                            int dy = w.y - dragOffset.y - o.a.y;
                            o.a.translate(dx, dy);
                            o.b.translate(dx, dy);
                        } else if (selectedShape instanceof TextShape t) {
                            t.pos.x = w.x - dragOffset.x;
                            t.pos.y = w.y - dragOffset.y;
                        }
                        repaint();
                        return;
                    }
                    // === Zeichnen Vorschau ===
                    if (mode == Mode.PEN && lastWorld != null) {
                        shapes.add(new LineShape(lastWorld, w, color, penSize));
                        lastWorld = w;
                        repaint();
                    } else {
                        dragNowWorld = w; // Vorschau
                        repaint();
                    }
                }
            });

            addKeyListener(new KeyAdapter() {
                @Override public void keyTyped(KeyEvent e) {
                    if (!typing) return;
                    char c = e.getKeyChar();
                    if (c == KeyEvent.VK_ENTER) {
                        drawText(textBuffer.toString(), textStartWorld);
                        typing = false;
                        textStartWorld = null;
                        textBuffer.setLength(0);
                        repaint();
                    } else if (c == KeyEvent.VK_BACK_SPACE) {
                        if (textBuffer.length() > 0) textBuffer.setLength(textBuffer.length() - 1);
                        repaint();
                    } else {
                        textBuffer.append(c);
                        repaint();
                    }
                }
                @Override public void keyPressed(KeyEvent e) {
                    // === Entf löscht Auswahl ===
                    if (e.getKeyCode() == KeyEvent.VK_DELETE && selectedShape != null) {
                        shapes.remove(selectedShape);
                        selectedShape = null;
                        repaint();
                    }
                }
            });
        }

        // Koordinaten
        Point toWorld(Point s) {
            try {
                AffineTransform at = viewTransform();
                AffineTransform inv = at.createInverse();
                Point p = new Point();
                inv.transform(s, p);
                return p;
            } catch (Exception ex) { return new Point(s); }
        }
        AffineTransform viewTransform() {
            AffineTransform at = new AffineTransform();
            at.translate(offX, offY);
            at.scale(scale, scale);
            return at;
        }

        // Zoom
        void onWheel(MouseWheelEvent e) {
            double prev = scale;
            double factor = (e.getPreciseWheelRotation() < 0) ? 1.1 : 0.9;
            scale = Math.max(0.1, Math.min(10.0, scale * factor));
            Point p = e.getPoint();
            offX = p.x - (p.x - offX) * (scale / prev);
            offY = p.y - (p.y - offY) * (scale / prev);
            repaint();
        }
        public void saveImage(File file) {
            BufferedImage image = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = image.createGraphics();
            paint(g2);
            g2.dispose();
            try {
                ImageIO.write(image, "png", file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void loadImage(File file) {
            try {
                BufferedImage image = ImageIO.read(file);
                Graphics g = getGraphics();
                g.drawImage(image, 0, 0, this);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Zeichnen / Commit
        void commitShape(Point a, Point b) {
            switch (mode) {
                case LINE -> shapes.add(new LineShape(a, b, color, penSize));
                case RECT -> shapes.add(new RectShape(a, b, color, penSize));
                case OVAL -> shapes.add(new OvalShape(a, b, color, penSize));
                default -> {}
            }
        }
        void drawText(String text, Point pos) {
            Font f = new Font(fontFamily, fontStyle, fontSize);
            shapes.add(new TextShape(text, pos, textColor, f));
        }

        @Override protected void paintComponent(Graphics g) {
            if (isOpaque()) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
            }

            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.transform(viewTransform());

            // gespeicherte Shapes
            for (DrawingShape s : shapes) s.draw(g2);

            // === Auswahlrahmen ===
            if (selectedShape != null) {
                g2.setColor(new Color(60,120,255,120));
                g2.setStroke(new BasicStroke(2f));
                if (selectedShape instanceof LineShape l) {
                    g2.drawLine(l.a.x, l.a.y, l.b.x, l.b.y);
                } else if (selectedShape instanceof RectShape r) {
                    g2.drawRect(Math.min(r.a.x, r.b.x), Math.min(r.a.y, r.b.y),
                        Math.abs(r.a.x - r.b.x), Math.abs(r.a.y - r.b.y));
                } else if (selectedShape instanceof OvalShape o) {
                    g2.drawOval(Math.min(o.a.x, o.b.x), Math.min(o.a.y, o.b.y),
                        Math.abs(o.a.x - o.b.x), Math.abs(o.a.y - o.b.y));
                } else if (selectedShape instanceof TextShape t) {
                    Rectangle bounds = getTextBounds(t.pos, t.text);
                    if (bounds != null) g2.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
                }
            }

            // Vorschau
            if (dragStartWorld != null && dragNowWorld != null && mode != Mode.PEN && mode != Mode.TEXT) {
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 180));
                g2.setStroke(new BasicStroke(penSize));
                switch (mode) {
                    case LINE -> g2.drawLine(dragStartWorld.x, dragStartWorld.y, dragNowWorld.x, dragNowWorld.y);
                    case RECT -> g2.drawRect(Math.min(dragStartWorld.x, dragNowWorld.x), Math.min(dragStartWorld.y, dragNowWorld.y),
                            Math.abs(dragNowWorld.x - dragStartWorld.x), Math.abs(dragNowWorld.y - dragStartWorld.y));
                    case OVAL -> g2.drawOval(Math.min(dragStartWorld.x, dragNowWorld.x), Math.min(dragStartWorld.y, dragNowWorld.y),
                            Math.abs(dragNowWorld.x - dragStartWorld.x), Math.abs(dragNowWorld.y - dragStartWorld.y));
                }
            }

            // Live-Tippen
            if (typing && textStartWorld != null) {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(textColor);
                g2.setFont(new Font(fontFamily, fontStyle, fontSize));
                String preview = textBuffer.toString() + "\u2588";
                g2.drawString(preview, textStartWorld.x, textStartWorld.y);
            }
            g2.dispose();
        }

        // === Hilfsmethoden für Auswahl und Textfeld ===
        private boolean shapeContains(DrawingShape s, Point p) {
            if (s instanceof LineShape l) {
                double dist = ptSegDist(l.a.x, l.a.y, l.b.x, l.b.y, p.x, p.y);
                return dist < Math.max(8, l.size + 6);
            } else if (s instanceof RectShape r) {
                Rectangle rect = new Rectangle(Math.min(r.a.x, r.b.x), Math.min(r.a.y, r.b.y),
                        Math.abs(r.a.x - r.b.x), Math.abs(r.a.y - r.b.y));
                return rect.contains(p);
            } else if (s instanceof OvalShape o) {
                Ellipse2D oval = new Ellipse2D.Double(Math.min(o.a.x, o.b.x), Math.min(o.a.y, o.b.y),
                        Math.abs(o.a.x - o.b.x), Math.abs(o.a.y - o.b.y));
                return oval.contains(p);
            } else if (s instanceof TextShape t) {
                Rectangle bounds = getTextBounds(t.pos, t.text);
                return bounds != null && bounds.contains(p);
            }
            return false;
        }
        private double ptSegDist(int x1, int y1, int x2, int y2, int px, int py) {
            double dx = x2 - x1, dy = y2 - y1;
            double len2 = dx*dx + dy*dy;
            if (len2 == 0) return Point.distance(x1, y1, px, py);
            double t = ((px-x1)*dx + (py-y1)*dy) / len2;
            t = Math.max(0, Math.min(1, t));
            double nx = x1 + t*dx, ny = y1 + t*dy;
            return Point.distance(nx, ny, px, py);
        }
        private Rectangle getTextBounds(Point pos, String text) {
            if (pos == null || text == null) return null;
            FontMetrics fm = getFontMetrics(new Font(fontFamily, fontStyle, fontSize));
            int w = fm.stringWidth(text);
            int h = fm.getHeight();
            return new Rectangle(pos.x, pos.y - h, w, h);
        }
    }

    // ====== UI ======
    final CanvasView cv = new CanvasView();

    // Top UI
    private JPanel topStack;     // enthält topBar + dropHost
    private JPanel topBar;       // Row mit Buttons
    private JPanel dropHost;     // „Parkplatz“ für Panels (bleibt 0px hoch)
    private JPanel colorPanel;   // HSV + Pen-Size (Mitte/pencil)
    private JPanel modePanel;    // Mode-Auswahl (links/ecke)
    private JPanel textPanel;    // Text-Optionen (rechts/scale)

    // Runde Hauptbuttons
    private Roundheadbutton btnModes, btnColor, btnText;

    // === Overlay/Popup über GlassPane ===
    private JComponent glass;
    private JPanel visibleDropdown = null;
    private JComponent visibleAnchor = null;
    private int visibleW = 0, visibleH = 0;

    // autosave file + timer
    private final File autosaveFile = new File(System.getProperty("user.home"), ".canvas_autosave.cvs");
    private Timer autosaveTimer;

    public canvasex() {
        super("Freeplay"); // App-Name
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        try {
            Image icon = Toolkit.getDefaultToolkit().getImage("icon.png");
            setIconImage(icon);
        } catch (Exception ignored) {}

        setLayout(new BorderLayout());

        // IMPORTANT: initialize glass (GlassPane) FIRST so hideAllDropdowns() can safely use it
        glass = new JPanel(null);
        glass.setOpaque(false);
        glass.setLayout(null);
        glass.setVisible(false);
        setGlassPane(glass);

        // ===== Top-Bereich (Stack) =====
        topStack = new JPanel(new BorderLayout());
        //add(topStack, BorderLayout.NORTH);
        topStack.setOpaque(false);
        topStack.setBackground(new Color(255,255,255,0));
        topStack.setBounds(0, 0, getWidth(), 80); // Höhe ggf. anpassen
        //layeredPane.add(topStack, JLayeredPane.PALETTE_LAYER);

        // Top-Bar: links "Neu", zentriert 3 runde Buttons
        topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);
        

        topBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 3, 12));
        topBar.setBackground(new Color(255,255,255,255)); //rgb a is broken fix it :(
        topStack.add(topBar, BorderLayout.NORTH);//shouldnt center mid buttons

        // Ganz links: Reset + Save/Load
        JButton clearBtn = new JButton("New");
        clearBtn.addActionListener(e -> {
            cv.shapes.clear();
            cv.repaint();
            cv.requestFocusInWindow();
        });

        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> saveProjectToFile());

        JButton loadBtn = new JButton("Load");
        loadBtn.addActionListener(e -> loadProjectFromFile());

        JPanel leftBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        JPanel rightBoxup = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        JPanel rightBoxdown = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        
        leftBox.setOpaque(false);
        leftBox.add(clearBtn);
        rightBoxdown.setOpaque(false);
        rightBoxup.setOpaque(false);
        rightBoxup.add(saveBtn);
        rightBoxdown.add(loadBtn);
        
        topBar.add(leftBox, BorderLayout.EAST);
        topBar.add(rightBoxup, BorderLayout.WEST);
        topBar.add(rightBoxdown, BorderLayout.SOUTH); // ...nach der Erstellung von rightBoxup und rightBoxdown...
        
        // Vertikaler Container für beide Boxen
        JPanel rightBoxColumn = new JPanel();
        rightBoxColumn.setOpaque(false);
        rightBoxColumn.setLayout(new BoxLayout(rightBoxColumn, BoxLayout.Y_AXIS));
        rightBoxColumn.add(rightBoxup);
        rightBoxColumn.add(rightBoxdown);
        
        // Füge die vertikale Box an die gewünschte Stelle ein:
        topBar.add(rightBoxColumn, BorderLayout.WEST);
        
        // Entferne die bisherigen Zeilen:
        // topBar.add(rightBoxup, BorderLayout.WEST);
        // topBar.add(rightBoxdown, BorderLayout.NORTH);.NORTH);

        // Mitte: 3 runde Buttons (ecke /pencil / scale)
        JPanel centerRound = new JPanel();
        centerRound.setOpaque(false);
        centerRound.setLayout(new FlowLayout(FlowLayout.CENTER, 18, 0));

        btnModes = new Roundheadbutton("icons/ecke.svg", 28);
        btnColor = new Roundheadbutton("icons/pencil.svg", 28);
        btnText  = new Roundheadbutton("icons/scale.svg", 28);

        centerRound.add(btnModes);
        centerRound.add(btnColor);
        centerRound.add(btnText);
        topBar.add(centerRound, BorderLayout.CENTER);

        // dropHost (bleibt 0 Höhe, Panels werden dort nur geparkt)
        dropHost = new JPanel(null) { @Override public boolean isOpaque() { return false; } };
        dropHost.setPreferredSize(new Dimension(10, 0)); // dauerhaft eingefahren
        topStack.add(dropHost, BorderLayout.CENTER);

        // ===== Dropdown-Panels bauen =====
        colorPanel = buildColorPanel(); // HSV + PenSize
        modePanel  = buildModePanel();  // runde Mode-Buttons
        textPanel  = buildTextPanel();  // Font + Stil + Größe + HSV für Text

        // Im dropHost  (unsichtbar)
        dropHost.add(colorPanel);
        dropHost.add(modePanel);
        dropHost.add(textPanel);

        // safe: hide dropdowns (glass already initialized)
        hideAllDropdowns();

        // Klicks außerhalb schließen das Popup, innerhalb NICHT
        glass.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (visibleDropdown == null) return;
                Point pInPopup = SwingUtilities.convertPoint(glass, e.getPoint(), visibleDropdown);
                if (!visibleDropdown.contains(pInPopup)) hideAllDropdowns();
            }
        });

        // Button-Logik → Overlay direkt unter dem jeweiligen Button
        btnModes.addActionListener(e -> toggleDropdownOverlay(modePanel, btnModes, 180, 140));
        btnColor.addActionListener(e -> toggleDropdownOverlay(colorPanel, btnColor, 320,110));
        btnText.addActionListener(e -> toggleDropdownOverlay(textPanel,  btnText,  360, 240));

        // ===== Canvas als Center =====
        JScrollPane scroller = new JScrollPane(cv);
        //add(scroller, BorderLayout.CENTER);

        JLayeredPane layeredPane = new JLayeredPane();
        setContentPane(layeredPane);

        scroller.setBounds(0, 0, getWidth(), getHeight());
        layeredPane.add(scroller, JLayeredPane.DEFAULT_LAYER);

        topStack.setOpaque(false);
        topStack.setBackground(new Color(255,255,255,0));
        topStack.setBounds(0, 0, getWidth(), 80); // Höhe ggf. anpassen
        layeredPane.add(topStack, JLayeredPane.PALETTE_LAYER);

        // Bei Resize Popup neu positionieren
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                scroller.setBounds(0, 0, getWidth(), getHeight());
                topStack.setBounds(0, 0, getWidth(), 80); // gleiche Höhe wie oben
                reanchorVisibleDropdowns();
            }
        });

        // ===== Key binding: Ctrl+S -> save dialog
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, "saveProject");
        getRootPane().getActionMap().put("saveProject", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                saveProjectToFile();
            }
        });

        // ===== Autosave Timer (every 30s) -> silent save to autosaveFile
        autosaveTimer = new Timer(30_000, e -> {
            saveProjectSilent(autosaveFile);
        });
        autosaveTimer.setRepeats(true);
        autosaveTimer.start();

        // Save once on exit (best-effort)
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                saveProjectSilent(autosaveFile);
                super.windowClosing(e);
            }
        });
    }

    // ===== Utils: Icons / Round Buttons / Dropdowns =====
    private static ImageIcon loadSvgIcon(String path, int size) {
        try {
            byte[] svgBytes = Files.readAllBytes(Paths.get(path));
            TranscoderInput input = new TranscoderInput(new ByteArrayInputStream(svgBytes));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            TranscoderOutput output = new TranscoderOutput(out);

            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) size);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) size);
            transcoder.transcode(input, output);

            out.flush();
            BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
            return new ImageIcon(img);
        } catch (Exception e) {
            // fallback: return empty icon to avoid NPE
            return new ImageIcon(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB));
        }
    }

    // ==== Einheitliche runde Buttons mit Schatten ====
    static class RoundButton extends JButton {
        private boolean hover = false;

        RoundButton(String svgPath, int size) {
            super(loadSvgIcon(svgPath, size));
            setPreferredSize(new Dimension(size + 14, size + 14));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited (MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            Shape circle = new Ellipse2D.Double(2,2,w-4,h-4);

            paintSoftShadow(g2, circle, 3, 0.20f);
            g2.setColor(new Color(240,240,255,240));
            g2.fill(circle);
            // 4 no shadow -> clear color
            if (hover) {
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(60,120,255,200));
                g2.draw(circle);
            }
            g2.dispose();
            super.paintComponent(g);
        }

        @Override public boolean contains(int x, int y) {
            int r = Math.min(getWidth(), getHeight()) / 2;
            int cx = getWidth()/2, cy = getHeight()/2;
            int dx = x - cx, dy = y - cy;
            return dx*dx + dy*dy <= r*r;
        }
    }

    // Farb-Button (klein, rund, mit Hover + Shadow)
    static class ColorButton extends JButton {
        private boolean hover = false;
        private final Color baseColor;

        ColorButton(Color color, int size) {
            this.baseColor = color;
            setPreferredSize(new Dimension(size, size));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            Shape circle = new Ellipse2D.Double(2,2,w-4,h-4);

            paintSoftShadow(g2, circle, 3, 0.22f);
            g2.setColor(baseColor);
            g2.fill(circle);

            if (hover) {
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(60,120,255,200));
                g2.draw(circle);
            }
            g2.dispose();
        }

        @Override public boolean contains(int x, int y) {
            int r = Math.min(getWidth(), getHeight())/2;
            int cx = getWidth()/2, cy = getHeight()/2;
            int dx = x - cx, dy = y - cy;
            return dx*dx + dy*dy <= r*r;
        }
    }

    // Roundheadbutton (Hauptbuttons oben) reuse RoundButton
    static class Roundheadbutton extends RoundButton {
        Roundheadbutton(String svgPath, int size) { super(svgPath, size); }
    }

    // ==== Abgerundetes Panel mit Shadow + subtle background ====
    static class RoundedPanel extends JPanel {
        private final int arc;
        RoundedPanel(int arc) {
            this.arc = arc;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
            setLayout(new GridBagLayout());
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Shape rr = new RoundRectangle2D.Double(6, 6, Math.max(getWidth()-12,0), Math.max(getHeight()-12,0), arc, arc);

            paintSoftShadow(g2, rr, 10, 0.20f);
            g2.setColor(new Color(255,255,255, 230));
            g2.fill(rr);

            g2.setColor(new Color(255,255,255,10));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(rr);

            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ==== ShadowSlider with custom painting (rounded thumb + rounded track) ====
    static class ShadowSlider extends JSlider {
        ShadowSlider(int min, int max, int val) {
            super(min, max, val);
            setOpaque(false);
            setUI(new BasicSliderUI(this) {
                @Override public void paintTrack(Graphics g) { /* skip default */ }
                @Override public void paintThumb(Graphics g) { /* skip default */ }
            });
            setFocusable(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int trackH = 6;
            int left = 10;
            int right = w - 10;
            int trackY = h/2 - trackH/2;

            RoundRectangle2D track = new RoundRectangle2D.Double(left, trackY, right-left, trackH, trackH, trackH);
            g2.setColor(new Color(240,240,240,200));
            g2.fill(track);

            double range = getMaximum() - getMinimum();
            double pos = (getValue() - getMinimum()) / (range <= 0 ? 1.0 : range);
            int filledW = left + (int)((right-left) * pos);
            RoundRectangle2D filled = new RoundRectangle2D.Double(left, trackY, Math.max(2, filledW-left), trackH, trackH, trackH);
            g2.setColor(new Color(54,60,255,180));
            g2.fill(filled);

            int thumbX = filledW;
            int thumbY = h / 2;
            Ellipse2D thumb = new Ellipse2D.Double(thumbX - 10, thumbY - 10, 20, 20);

            paintSoftShadow(g2, thumb, 6, 0.25f);

            g2.setColor(Color.WHITE);
            g2.fill(thumb);
            g2.setColor(new Color(150,150,150,200));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(thumb);

            g2.dispose();
        }
    }

    // ==== Overlay logic ====
    private void toggleDropdownOverlay(JPanel panel, JComponent anchorBtn, int prefW, int prefH) {
        boolean willShow = (visibleDropdown != panel);
        hideAllDropdowns(); // clean glass

        if (willShow) {
            Container parent = panel.getParent();
            if (parent != null) parent.remove(panel);

            panel.setSize(prefW, prefH);
            Point pGL = calcPopupLocation(anchorBtn, prefW, prefH, 8);
            panel.setLocation(pGL);

            if (glass == null) {
                glass = (JComponent) getRootPane().getGlassPane();
                glass.setLayout(null);
            }

            glass.add(panel);
            panel.setVisible(true);
            glass.setVisible(true);

            visibleDropdown = panel;
            visibleAnchor = anchorBtn;
            visibleW = prefW; visibleH = prefH;

            glass.revalidate();
            glass.repaint();
            cv.requestFocusInWindow();
        }
    }

    private Point calcPopupLocation(JComponent anchor, int prefW, int prefH, int gap) {
        Point aOnGlass = SwingUtilities.convertPoint(anchor, 0, 0, glass);
        int x = aOnGlass.x + anchor.getWidth()/2 - prefW/2;
        int y = aOnGlass.y + anchor.getHeight() + gap;
        x = Math.max(8, Math.min(x, glass.getWidth() - prefW - 8));
        y = Math.max(8, Math.min(y, glass.getHeight() - prefH - 8));
        return new Point(x, y);
    }

    private void hideAllDropdowns() {
        if (visibleDropdown != null) {
            if (glass != null) {
                glass.remove(visibleDropdown);
            }
            if (dropHost != null) dropHost.add(visibleDropdown);
            visibleDropdown.setVisible(false);
        }
        visibleDropdown = null;
        visibleAnchor = null;
        visibleW = visibleH = 0;

        if (glass != null) {
            glass.setVisible(false);
            glass.revalidate();
            glass.repaint();
        }

        setDropHostHeight(0);
    }

    private void reanchorVisibleDropdowns() {
        if (visibleDropdown != null && visibleAnchor != null) {
            Point p = calcPopupLocation(visibleAnchor, visibleW, visibleH, 8);
            visibleDropdown.setLocation(p);
            if (glass != null) {
                glass.revalidate();
                glass.repaint();
            }
        } else {
            setDropHostHeight(0);
        }
    }

    private void setDropHostHeight(int h) {
        if (dropHost != null) {
            dropHost.setPreferredSize(new Dimension(10, 0));
            topStack.revalidate();
        }
    }

    // ====== Panels bauen ======

    // Mitte: Farbwahl-Buttons + Strichstärke
    private JPanel buildColorPanel() {
        RoundedPanel p = new RoundedPanel(20);
        p.setLayout(new BorderLayout(8, 8));

        // colors row
        JPanel colorsRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        colorsRow.setOpaque(false);

        Color[] colors = {
            Color.BLACK, Color.RED, Color.BLUE, Color.GREEN,
            Color.YELLOW, Color.ORANGE, Color.WHITE
        };
        String[] names = {
            "Schwarz", "Rot", "Blau", "Grün", "Gelb", "Orange", "Weiß"
        };

        for (int i = 0; i < colors.length; i++) {
            final Color c = colors[i];
            ColorButton btn = new ColorButton(c, 34);
            btn.setToolTipText(names[i]);
            btn.addActionListener(e -> {
                cv.color = c;
                cv.requestFocusInWindow();
                cv.repaint();
            });
            colorsRow.add(btn);
        }

        // stroke row (uses ShadowSlider)
        JPanel strokeRow = new JPanel(new BorderLayout(6, 0));
        strokeRow.setOpaque(false);
        JLabel lw = new JLabel(new ImageIcon("fett.png"));
        ShadowSlider sw = new ShadowSlider(1, 64, cv.penSize);
        sw.setPreferredSize(new Dimension(220, 36));
        strokeRow.add(lw, BorderLayout.WEST);
        strokeRow.add(sw, BorderLayout.CENTER);

        sw.addChangeListener(e -> {
            cv.penSize = sw.getValue();
            cv.requestFocusInWindow();
            cv.repaint();
        });

        p.add(colorsRow, BorderLayout.CENTER);
        p.add(strokeRow, BorderLayout.SOUTH);

        p.setVisible(false);
        return p;
    }

    // Links: Mode-Auswahl (Buttons mit Icons)
    private JPanel buildModePanel() {
        RoundedPanel p = new RoundedPanel(20);
        p.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 10));
        RoundButton penBtn   = new RoundButton("icons/pen.svg", 26);
        RoundButton lineBtn  = new RoundButton("icons/line.svg", 26);
        RoundButton rectBtn  = new RoundButton("icons/rect.svg", 26);
        RoundButton ovalBtn  = new RoundButton("icons/kreis.svg", 26);
        RoundButton textBtn  = new RoundButton("icons/text.svg", 26);

        penBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.PEN;   cv.typing=false; cv.requestFocusInWindow(); });
        lineBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.LINE;  cv.typing=false; cv.requestFocusInWindow(); });
        rectBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.RECT;  cv.typing=false; cv.requestFocusInWindow(); });
        ovalBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.OVAL;  cv.typing=false; cv.requestFocusInWindow(); });
        textBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.TEXT;  cv.typing=false; cv.requestFocusInWindow(); });

        p.add(penBtn); p.add(lineBtn); p.add(rectBtn); p.add(ovalBtn); p.add(textBtn);
        p.setSize(300, 120);
        p.setVisible(false);
        return p;
    }

    // Rechts: Text-Optionen (Font, Stil, Größe, HSV → wirkt auf cv.textColor)
    private JPanel buildTextPanel() {
        RoundedPanel p = new RoundedPanel(20);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6,6,6,6);
        gc.gridx=0; gc.gridy=0; gc.anchor = GridBagConstraints.WEST;

        JLabel lsz = new JLabel("Size");
        lsz.setForeground(Color.BLACK);
        SpinnerNumberModel fsizeModel = new SpinnerNumberModel(cv.fontSize, 6, 400, 4);
        JSpinner fsizeSpin = new JSpinner(fsizeModel);

        JLabel lth = new JLabel("Hue");
        lth.setForeground(Color.BLACK);
        ShadowSlider sh = new ShadowSlider(0,360, 100);
        JLabel lts = new JLabel("Sat");
        lts.setForeground(Color.BLACK);
        ShadowSlider ss = new ShadowSlider(0,100, 100);
        JLabel ltb = new JLabel("Bri");
        ltb.setForeground(Color.BLACK);
        ShadowSlider sb = new ShadowSlider(0,100, 100);
        JPanel preview = new JPanel(); preview.setPreferredSize(new Dimension(36, 24));
        preview.setBorder(BorderFactory.createLineBorder(new Color(120,120,120)));

        gc.gridx=0; gc.gridy++; gc.weightx=0; gc.fill=GridBagConstraints.NONE; p.add(lsz, gc);
        gc.gridx=1; gc.fill=GridBagConstraints.HORIZONTAL; gc.weightx=1; p.add(fsizeSpin, gc);

        gc.gridx=0; gc.gridy++; gc.weightx=0; p.add(lth, gc);
        gc.gridx=1; gc.weightx=1; gc.fill=GridBagConstraints.HORIZONTAL; p.add(sh, gc);
        gc.gridx=0; gc.gridy++; gc.weightx=0; gc.fill=GridBagConstraints.NONE; p.add(lts, gc);
        gc.gridx=1; gc.weightx=1; gc.fill=GridBagConstraints.HORIZONTAL; p.add(ss, gc);
        gc.gridx=0; gc.gridy++; gc.weightx=0; gc.fill=GridBagConstraints.NONE; p.add(ltb, gc);
        gc.gridx=1; gc.weightx=1; gc.fill=GridBagConstraints.HORIZONTAL; p.add(sb, gc);

        gc.gridx=0; gc.gridy++; gc.gridwidth=2; gc.weightx=0; gc.fill=GridBagConstraints.NONE;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6,0)); row.setOpaque(false);
        JLabel pvLbl = new JLabel("Preview:");
        pvLbl.setForeground(Color.BLACK);
        row.add(pvLbl); row.add(preview);
        p.add(row, gc);

        // logic
        fsizeSpin.addChangeListener(e -> { cv.fontSize = (Integer) fsizeSpin.getValue(); cv.requestFocusInWindow(); cv.repaint(); });

        Runnable apply = () -> {
            float h = sh.getValue()/360f;
            float s = ss.getValue()/100f;
            float b = sb.getValue()/100f;
            cv.textColor = Color.getHSBColor(h,s,b);
            preview.setBackground(cv.textColor);
            cv.requestFocusInWindow();
            cv.repaint();
        };
        sh.addChangeListener(e -> apply.run());
        ss.addChangeListener(e -> apply.run());
        sb.addChangeListener(e -> apply.run());

        // reflect start color
        float[] hsb = Color.RGBtoHSB(cv.textColor.getRed(), cv.textColor.getGreen(), cv.textColor.getBlue(), null);
        sh.setValue(Math.round(hsb[0]*360));
        ss.setValue(Math.round(hsb[1]*100));
        sb.setValue(Math.round(hsb[2]*100));
        preview.setBackground(cv.textColor);

        p.setVisible(false);
        return p;
    }

    // ===== Persistence: save/load project =====

    // Save with dialog (Ctrl+S mapped to this)
    private void saveProjectToFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save project");
        fc.setSelectedFile(new File("mycanvas.cvs"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".cvs")) file = new File(file.getParentFile(), file.getName() + ".cvs");
            saveProjectSilent(file);
            JOptionPane.showMessageDialog(this, "Projekt Saved:\n" + file.getAbsolutePath());
        }
    }

    // Silent save (no dialogs) - used by autosave and on-exit
    private void saveProjectSilent(File file) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            ProjectData pd = new ProjectData(cv.shapes, cv.scale, cv.offX, cv.offY,
                    cv.penSize, cv.color, cv.textColor,
                    cv.fontSize, cv.fontFamily, cv.fontStyle);
            oos.writeObject(pd);
            oos.flush();
            // no dialog
        } catch (Exception ex) {
            // Log to stderr but don't spam user on autosave
            System.err.println("Error saving project to " + file.getAbsolutePath() + ": " + ex.getMessage());
            // optionally show dialog if user explicitly saved via dialog (handled in saveProjectToFile)
        }
    }

    private void loadProjectFromFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Load project");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                Object o = ois.readObject();
                if (o instanceof ProjectData pd) {
                    cv.shapes.clear();
                    cv.shapes.addAll(pd.shapes);
                    cv.scale = pd.scale;
                    cv.offX = pd.offX; cv.offY = pd.offY;
                    cv.penSize = pd.penSize;
                    cv.color = pd.drawColor;
                    cv.textColor = pd.textColor;
                    cv.fontSize = pd.fontSize;
                    cv.fontFamily = pd.fontFamily;
                    cv.fontStyle = pd.fontStyle;
                    cv.repaint();
                    JOptionPane.showMessageDialog(this, "Project loaded:\n" + file.getAbsolutePath());
                } else {
                    JOptionPane.showMessageDialog(this, "File not found.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ===== main =====
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new canvasex().setVisible(true));
    }
}
