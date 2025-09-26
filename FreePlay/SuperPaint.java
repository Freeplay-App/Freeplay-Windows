import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage; // falls anderswo benötigt (master vip hate recht)
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SuperPaint extends JFrame {

    // ====== Canvas / Shapes
    interface DrawingShape { void draw(Graphics2D g); }

    static class LineShape implements DrawingShape {
        Point a, b; Color color; int size;
        LineShape(Point a, Point b, Color c, int s) { this.a=a; this.b=b; this.color=c; this.size=s; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(size, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(a.x, a.y, b.x, b.y);
        }
    }

    static class RectShape implements DrawingShape {
        Point a, b; Color color; int size;
        RectShape(Point a, Point b, Color c, int s) { this.a=a; this.b=b; this.color=c; this.size=s; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(size));
            g.drawRect(Math.min(a.x,b.x), Math.min(a.y,b.y), Math.abs(a.x-b.x), Math.abs(a.y-b.y));
        }
    }

    static class OvalShape implements DrawingShape {
        Point a, b; Color color; int size;
        OvalShape(Point a, Point b, Color c, int s) { this.a=a; this.b=b; this.color=c; this.size=s; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(size));
            g.drawOval(Math.min(a.x,b.x), Math.min(a.y,b.y), Math.abs(a.x-b.x), Math.abs(a.y-b.y));
        }
    }

    static class TextShape implements DrawingShape {
        String text; Point pos; Color color; Font font;
        TextShape(String t, Point p, Color c, Font f) { this.text=t; this.pos=p; this.color=c; this.font=f; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(color);
            g.setFont(font);
            g.drawString(text, pos.x, pos.y);
        }
    }

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

        CanvasView() {
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
                    if (mode == Mode.TEXT) {
                        typing = true;
                        textBuffer.setLength(0);
                        textStartWorld = w;
                        repaint();
                        return;
                    }
                    dragStartWorld = w;
                    lastWorld = w;
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        panning = false; setCursor(Cursor.getDefaultCursor());
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
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.transform(viewTransform());

            // gespeicherte Shapes
            for (DrawingShape s : shapes) s.draw(g2);

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

    public SuperPaint() {
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
        add(topStack, BorderLayout.NORTH);

        // Top-Bar: links "Neu", zentriert 3 runde Buttons
        topBar = new JPanel(new BorderLayout());
        topBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        topBar.setBackground(new Color(255, 255, 255));
        topStack.add(topBar, BorderLayout.NORTH);

        // Ganz links: Reset
        JButton clearBtn = new JButton("New");
        clearBtn.addActionListener(e -> {
            cv.shapes.clear();
            cv.repaint();
            cv.requestFocusInWindow();
        });
        JPanel leftBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftBox.setOpaque(false);
        leftBox.add(clearBtn);
        topBar.add(leftBox, BorderLayout.WEST);

        // Mitte: 3 runde Buttons (ecke /pencil / scale)
        JPanel centerRound = new JPanel();
        centerRound.setOpaque(false);
        centerRound.setLayout(new FlowLayout(FlowLayout.CENTER, 18, 0));

        btnModes = new Roundheadbutton("icons/ecke.svg", 40);
        btnColor = new Roundheadbutton("icons/pencil.svg", 40);
        btnText  = new Roundheadbutton("icons/scale.svg", 40);


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
        btnModes.addActionListener(e -> toggleDropdownOverlay(modePanel, btnModes, 330, 58));
        btnColor.addActionListener(e -> toggleDropdownOverlay(colorPanel, btnColor, 300,80));
        btnText.addActionListener(e -> toggleDropdownOverlay(textPanel,  btnText,  320, 220));

        // ===== Canvas als Center =====
        JScrollPane scroller = new JScrollPane(cv);
        add(scroller, BorderLayout.CENTER);

        // Bei Resize Popup neu positionieren
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                reanchorVisibleDropdowns();
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
        e.printStackTrace();
        return null;
    }
}

    
static class RoundButton extends JButton {
    private boolean hover = false;

    RoundButton(String svgPath, int size) {
        super(loadSvgIcon(svgPath, size));
        setPreferredSize(new Dimension(size + 10, size + 10));
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
        g2.setColor(new Color(255,255,255,243));
        g2.fillOval(1,1,w-2,h-2);

        if (hover) {
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(40,120,255));
            g2.drawOval(2,2,w-4,h-4);
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


// Farb-Button (klein, rund, mit Hover)
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

        // Kreis mit Farbe
        g2.setColor(baseColor);
        g2.fillOval(1, 1, w - 2, h - 2);

        // Hover-Ring (blau)
        if (hover) {
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(new Color(40, 120, 255));
            g2.drawOval(2, 2, w - 4, h - 4);
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


    

    // Icon BUtton für Offene leiste

     static class Roundheadbutton extends JButton {
    private boolean hover = false;

    Roundheadbutton(String svgPath, int size) {
        super(loadSvgIcon(svgPath, size));
        setPreferredSize(new Dimension(size + 15, size + 15));
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
        g2.setColor(new Color(255,255,255,243));
        g2.fillOval(1,1,w-2,h-2);

        if (hover) {
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(new Color(40,120,255));
            g2.drawOval(2,2,w-4,h-4);
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


    // abgerundetes schwebendes Panel (halbtransparent, ohne Rand)
    static class RoundedPanel extends JPanel {
        private final int arc;
        RoundedPanel(int arc) {
            this.arc = arc;
            setOpaque(false);
            setLayout(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape rr = new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), arc, arc);
            g2.setColor(new Color( 50,255,255,60)); // halbtransparente
            g2.fill(rr);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ==== Overlay-Logik (GlassPane) ====
    private void toggleDropdownOverlay(JPanel panel, JComponent anchorBtn, int prefW, int prefH) {
        boolean willShow = (visibleDropdown != panel);
        hideAllDropdowns(); // macht Glas sauber

        if (willShow) {
            // Panel ggf. aus altem Parent lösen und in GlassPane einsetzen
            Container parent = panel.getParent();
            if (parent != null) parent.remove(panel);

            panel.setSize(prefW, prefH);
            Point pGL = calcPopupLocation(anchorBtn, prefW, prefH, 8);
            panel.setLocation(pGL);

            // safety: ensure glass not null
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

    // Popup-Position unter dem Anchor mittig, am Rand begrenzen
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

        setDropHostHeight(0); // bleibt immer 0
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

    // Mitte: HSV + Strichstärke (wirkt auf cv.color / cv.penSize)
    // ===== Farbspektrum Panel =====
class ColorSpectrumPanel extends JPanel {
    private float hue = 0f, sat = 1f, bri = 1f;
    private final int size = 200;
    private final Runnable onChange;
    private BufferedImage img;

    ColorSpectrumPanel(Runnable onChange) {
        this.onChange = onChange;
        setPreferredSize(new Dimension(size, size));

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { update(e); }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { update(e); }
        });
    }

    private void update(MouseEvent e) {
        float x = Math.max(0, Math.min(size-1, e.getX()));
        float y = Math.max(0, Math.min(size-1, e.getY()));
        hue = x / (float) size;
        sat = 1f - (y / (float) size);
        onChange.run();
        repaint();
    }

    public Color getColor(float briOverride) {
        return Color.getHSBColor(hue, sat, briOverride);
    }

    @Override protected void paintComponent(Graphics g) {
        if (img == null) {
            img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    float h = x / (float) size;
                    float s = 1f - (y / (float) size);
                    img.setRGB(x, y, Color.HSBtoRGB(h, s, bri));
                }
            }
        }
        g.drawImage(img, 0, 0, null);
    }
}


// Mitte: Farbwahl-Buttons + Strichstärke
private JPanel buildColorPanel() {
    RoundedPanel p = new RoundedPanel(26);
    p.setLayout(new BorderLayout(8, 8));

    // === Farben-Panel ===
    JPanel colorsRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
    colorsRow.setOpaque(false);

    // Hauptfarben
    Color[] colors = {
        Color.BLACK, Color.RED, Color.BLUE, Color.GREEN,
        Color.YELLOW, Color.ORANGE, Color.WHITE
    };
    String[] names = {
        "Schwarz", "Rot", "Blau", "Grün", "Gelb", "Orange", "Weiß"
    };

    for (int i = 0; i < colors.length; i++) {
        final Color c = colors[i];
        ColorButton btn = new ColorButton(c, 30); // Größe = 30px
        btn.setToolTipText(names[i]);
        btn.addActionListener(e -> {
            cv.color = c;
            cv.requestFocusInWindow();
            cv.repaint();
        });
        colorsRow.add(btn);
    }

    // === Strichstärke ===
    JPanel strokeRow = new JPanel(new BorderLayout(6, 0));
    strokeRow.setOpaque(false);
    JLabel lw = new JLabel(new ImageIcon("fett.png")); // Icon statt Text
    JSlider sw = new JSlider(1, 64, cv.penSize);
    strokeRow.add(lw, BorderLayout.WEST);
    strokeRow.add(sw, BorderLayout.CENTER);

    sw.addChangeListener(e -> {
        cv.penSize = sw.getValue();
        cv.requestFocusInWindow();
        cv.repaint();
    });

    // === Zusammensetzen ===
    p.add(colorsRow, BorderLayout.CENTER);
    p.add(strokeRow, BorderLayout.SOUTH);

    p.setVisible(false);
    return p;
}


    // Links: Mode-Auswahl (Buttons mit Icons)
    private JPanel buildModePanel() {
        RoundedPanel p = new RoundedPanel(26);
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
        p.setSize(250, 120);
        p.setVisible(false);
        return p;
    }

    // Rechts: Text-Optionen (Font, Stil, Größe, HSV → wirkt auf cv.textColor)
    private JPanel buildTextPanel() {
        RoundedPanel p = new RoundedPanel(26);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4,4,4,4);
        gc.gridx=0; gc.gridy=0; gc.anchor = GridBagConstraints.WEST;

        // Font-Family
        

        // Stil
        

        // Größe
        JLabel lsz = new JLabel("Größe");
        SpinnerNumberModel fsizeModel = new SpinnerNumberModel(cv.fontSize, 6, 400, 1);
        JSpinner fsizeSpin = new JSpinner(fsizeModel);

        // HSV für Textfarbe
        JLabel lth = new JLabel("Hue");
        JSlider sh = new JSlider(0,360, 0);
        JLabel lts = new JLabel("Sat");
        JSlider ss = new JSlider(0,100, 100);
        JLabel ltb = new JLabel("Bri");
        JSlider sb = new JSlider(0,100, 100);
        JPanel preview = new JPanel(); preview.setPreferredSize(new Dimension(36, 24));
        preview.setBorder(BorderFactory.createLineBorder(new Color(180,180,180)));

        
        gc.gridx=0; gc.gridy++; gc.weightx=0; gc.fill=GridBagConstraints.NONE; p.add(lsz, gc);
        gc.gridx=1; gc.fill=GridBagConstraints.HORIZONTAL; gc.weightx=1; p.add(fsizeSpin, gc);

        gc.gridx=0; gc.gridy++; gc.weightx=0; p.add(lth, gc);
        gc.gridx=1; gc.weightx=1; gc.fill=GridBagConstraints.HORIZONTAL; p.add(sh, gc);
        gc.gridx=0; gc.gridy++; gc.weightx=0; gc.fill=GridBagConstraints.NONE; p.add(lts, gc);
        gc.gridx=1; gc.weightx=1; gc.fill=GridBagConstraints.HORIZONTAL; p.add(ss, gc);
        gc.gridx=0; gc.gridy++; gc.weightx=0; gc.fill=GridBagConstraints.NONE; p.add(ltb, gc);
        gc.gridx=1; gc.weightx=1; gc.fill=GridBagConstraints.HORIZONTAL; p.add(sb, gc);

        gc.gridx=0; gc.gridy++; gc.gridwidth=2; gc.weightx=0; gc.fill=GridBagConstraints.NONE;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0,0)); row.setOpaque(false);
        row.add(new JLabel("Vorschau: ")); row.add(preview);
        p.add(row, gc);

        // Logik
        
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

        // Startfarbe spiegeln
        float[] hsb = Color.RGBtoHSB(cv.textColor.getRed(), cv.textColor.getGreen(), cv.textColor.getBlue(), null);
        sh.setValue(Math.round(hsb[0]*360));
        ss.setValue(Math.round(hsb[1]*100));
        sb.setValue(Math.round(hsb[2]*100));
        preview.setBackground(cv.textColor);

        p.setVisible(false);
        return p;
    }

    // ===== main =====
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new SuperPaint().setVisible(true));
    }
}
