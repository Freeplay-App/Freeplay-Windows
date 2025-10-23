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
import java.awt.datatransfer.StringSelection;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;

public class canvasex extends JFrame {

    interface DrawingShape { void draw(Graphics2D g); }

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
            FontMetrics fm = g.getFontMetrics(font);
            int lineHeight = fm.getHeight();
            String[] lines = (text == null) ? new String[] { "" } : text.split("\n", -1);
            int y = pos.y;
            for (String line : lines) {
                g.drawString(line, pos.x, y);
                y += lineHeight;
            }
        }
    }

    private static void paintSoftShadow(Graphics2D g2, Shape s, int blurRadius, float baseAlpha) {
        if (blurRadius <= 0) return;
        AffineTransform oldT = g2.getTransform();
        Composite oldC = g2.getComposite();
        Object oldHint = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double step = 1.0;
        for (int layer = blurRadius; layer >= 1; layer--) {
            for (int dx = -layer; dx <= layer; dx++) {
                for (int dy = -layer; dy <= layer; dy++) {
                    double dist = Math.hypot(dx, dy);
                    if (dist > layer) continue;
                    float alpha = baseAlpha * (float) (1.0 - (dist / (blurRadius + 0.5)));
                    alpha = Math.max(0f, Math.min(1f, alpha));
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    AffineTransform t = AffineTransform.getTranslateInstance(dx * step, dy * step);
                    g2.transform(t);
                    g2.fill(s);
                    g2.setTransform(oldT);
                }
            }
        }
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, baseAlpha * 0.35f))));
        g2.fill(s);
        g2.setTransform(oldT);
        g2.setComposite(oldC);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldHint);
    }

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

    static class CanvasView extends JComponent {
        List<DrawingShape> shapes = new ArrayList<>();
        double scale = 1.0;
        double offX = 0, offY = 0;
        Point lastWorld = null;
        Point dragStartWorld = null;
        Point dragNowWorld = null;
        boolean panning = false;
        int panLastX, panLastY;
        enum Mode { NONE, PEN, LINE, RECT, OVAL, TEXT }
        Mode mode = Mode.PEN;
        Color color = Color.BLACK;
        Color textColor = Color.BLACK;
        int penSize = 4;
        String fontFamily = "Arial";
        int fontSize = 24;
        int fontStyle = Font.PLAIN;
        boolean typing = false;
        Point textStartWorld = null;
        StringBuilder textBuffer = new StringBuilder();
        int caretPos = 0;
        int selStart = -1, selEnd = -1;
        DrawingShape selectedShape = null;
        Point dragOffset = null;
        boolean resizing = false;
        int activeHandle = -1;
        Rectangle initialBounds = null;
        public boolean editingEnabled = false;

        CanvasView() {
            setOpaque(true);
            setBackground(Color.WHITE);
            setFocusable(true);
            addMouseWheelListener(this::onWheel);
            try {
                new java.awt.dnd.DropTarget(this, java.awt.dnd.DnDConstants.ACTION_COPY,
                    new java.awt.dnd.DropTargetAdapter() {
                        @Override public void drop(java.awt.dnd.DropTargetDropEvent dtde) {
                            try {
                                java.awt.datatransfer.Transferable tr = dtde.getTransferable();
                                if (tr.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                                    dtde.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY);
                                    @SuppressWarnings("unchecked")
                                    java.util.List<File> files = (java.util.List<File>) tr.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                                    for (File f : files) {
                                        try {
                                            BufferedImage img = ImageIO.read(f);
                                            if (img != null) {
                                                Point dropPt = dtde.getLocation();
                                                Point world = toWorld(dropPt);
                                                int iw = img.getWidth(), ih = img.getHeight();
                                                int max = 300;
                                                double scaleFactor = Math.min(1.0, Math.min((double)max/iw, (double)max/ih));
                                                int w = Math.max(32, (int)(iw * scaleFactor));
                                                int h = Math.max(32, (int)(ih * scaleFactor));
                                                ImageShape is = new ImageShape(img, world.x - w/2, world.y - h/2, w, h);
                                                shapes.add(is);
                                                selectedShape = is;
                                            }
                                        } catch (Exception ex) {
                                            ex.printStackTrace();
                                        }
                                    }
                                    dtde.dropComplete(true);
                                    repaint();
                                    requestFocusInWindow();
                                    return;
                                }
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                            dtde.rejectDrop();
                        }
                    }, true, null);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (SwingUtilities.isRightMouseButton(e)) {
                        panning = true; panLastX = e.getX(); panLastY = e.getY();
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        return;
                    }
                    Point w = toWorld(e.getPoint());
                    if (typing) {
                        Rectangle textBounds = getTextBounds(textStartWorld, textBuffer.toString());
                        if (textBounds == null || !textBounds.contains(w)) {
                            commitTyping();
                            return;
                        } else {
                            caretPos = caretIndexFromClick(textBuffer.toString(), textStartWorld, w);
                            clearSelection();
                            repaint();
                            return;
                        }
                    }
                    selectedShape = null;
                    if (editingEnabled) {
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
                                    if (e.getClickCount() == 2) {
                                        startEditingTextShape(t, w);
                                        return;
                                    }
                                } else if (s instanceof ImageShape im) {
                                    Rectangle bounds = im.getBounds();
                                    int handle = getHandleIndex(im, w);
                                    if (handle >= 0) {
                                        resizing = true;
                                        activeHandle = handle;
                                        initialBounds = new Rectangle(bounds);
                                        return;
                                    } else {
                                        dragOffset = new Point(w.x - bounds.x, w.y - bounds.y);
                                    }
                                }
                                repaint();
                                return;
                            }
                        }
                    }
                    if (!editingEnabled && selectedShape == null && mode == Mode.TEXT && !typing) {
                        typing = true;
                        textStartWorld = w;
                        textBuffer.setLength(0);
                        caretPos = 0;
                        clearSelection();
                        repaint();
                        return;
                    }
                    if (!editingEnabled) {
                        dragStartWorld = w;
                        lastWorld = w;
                    } else {
                        dragStartWorld = null;
                        lastWorld = null;
                    }
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        panning = false; setCursor(Cursor.getDefaultCursor());
                        return;
                    }
                    resizing = false;
                    activeHandle = -1;
                    initialBounds = null;
                    if (selectedShape != null) {
                        dragOffset = null;
                        return;
                    }
                    if (dragStartWorld == null) return;
                    Point w = toWorld(e.getPoint());
                    if (!editingEnabled) commitShape(dragStartWorld, w);
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
                    if (editingEnabled && selectedShape != null && dragOffset != null && !resizing) {
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
                        } else if (selectedShape instanceof ImageShape im) {
                            Rectangle b = im.getBounds();
                            b.x = w.x - dragOffset.x;
                            b.y = w.y - dragOffset.y;
                            im.setBounds(b);
                        }
                        repaint();
                        return;
                    }
                    if (editingEnabled && resizing && selectedShape instanceof ImageShape im && initialBounds != null) {
                        Rectangle nb = new Rectangle(initialBounds);
                        switch (activeHandle) {
                            case 0 -> {
                                int nx = Math.min(initialBounds.x + initialBounds.width - 8, w.x);
                                int ny = Math.min(initialBounds.y + initialBounds.height - 8, w.y);
                                nb.width = initialBounds.x + initialBounds.width - nx;
                                nb.height = initialBounds.y + initialBounds.height - ny;
                                nb.x = nx;
                                nb.y = ny;
                            }
                            case 1 -> {
                                int nx = Math.max(initialBounds.x + 8, w.x);
                                int ny = Math.min(initialBounds.y + initialBounds.height - 8, w.y);
                                nb.width = nx - initialBounds.x;
                                nb.height = initialBounds.y + initialBounds.height - ny;
                                nb.y = ny;
                            }
                            case 2 -> {
                                int nx = Math.max(initialBounds.x + 8, w.x);
                                int ny = Math.max(initialBounds.y + 8, w.y);
                                nb.width = nx - initialBounds.x;
                                nb.height = ny - initialBounds.y;
                            }
                            case 3 -> {
                                int nx = Math.min(initialBounds.x + initialBounds.width - 8, w.x);
                                int ny = Math.max(initialBounds.y + 8, w.y);
                                nb.width = initialBounds.x + initialBounds.width - nx;
                                nb.height = ny - initialBounds.y;
                                nb.x = nx;
                            }
                        }
                        nb.width = Math.max(16, nb.width);
                        nb.height = Math.max(16, nb.height);
                        im.setBounds(nb);
                        repaint();
                        return;
                    }
                    if (!editingEnabled) {
                        if (mode == Mode.PEN && lastWorld != null) {
                            shapes.add(new LineShape(lastWorld, w, color, penSize));
                            lastWorld = w;
                            repaint();
                        } else {
                            dragNowWorld = w;
                            repaint();
                        }
                    }
                }
            });

            addKeyListener(new KeyAdapter() {
                @Override public void keyTyped(KeyEvent e) {
                    if (!typing) return;
                    char c = e.getKeyChar();
                    if (c == '\b') {
                        return;
                    }
                    if (c == '\n' || c == '\r') {
                        insertText("\n");
                        return;
                    }
                    if (c >= 32) {
                        insertText(String.valueOf(c));
                    }
                }

                @Override public void keyPressed(KeyEvent e) {
                    if (typing) {
                        if (isCtrl(e) && e.getKeyCode() == KeyEvent.VK_C) {
                            copySelectionOrCaret();
                            return;
                        }
                        if (isCtrl(e) && e.getKeyCode() == KeyEvent.VK_V) {
                            pasteClipboard();
                            return;
                        }
                        if (isCtrl(e) && e.getKeyCode() == KeyEvent.VK_A) {
                            selStart = 0; selEnd = textBuffer.length();
                            caretPos = selEnd;
                            repaint();
                            return;
                        }
                        switch (e.getKeyCode()) {
                            case KeyEvent.VK_LEFT -> {
                                if (hasSelection() && !e.isShiftDown()) {
                                    caretPos = Math.min(selStart, selEnd);
                                    clearSelection();
                                } else {
                                    if (caretPos > 0) caretPos--;
                                    clearSelection();
                                }
                                repaint();
                                return;
                            }
                            case KeyEvent.VK_RIGHT -> {
                                if (hasSelection() && !e.isShiftDown()) {
                                    caretPos = Math.max(selStart, selEnd);
                                    clearSelection();
                                } else {
                                    if (caretPos < textBuffer.length()) caretPos++;
                                    clearSelection();
                                }
                                repaint();
                                return;
                            }
                            case KeyEvent.VK_UP -> {
                                moveCaretUpDown(-1);
                                clearSelection();
                                repaint();
                                return;
                            }
                            case KeyEvent.VK_DOWN -> {
                                moveCaretUpDown(1);
                                clearSelection();
                                repaint();
                                return;
                            }
                            case KeyEvent.VK_BACK_SPACE -> {
                                if (hasSelection()) {
                                    deleteSelection();
                                } else if (caretPos > 0) {
                                    textBuffer.deleteCharAt(caretPos - 1);
                                    caretPos--;
                                }
                                repaint();
                                return;
                            }
                            case KeyEvent.VK_DELETE -> {
                                if (hasSelection()) {
                                    deleteSelection();
                                } else if (caretPos < textBuffer.length()) {
                                    textBuffer.deleteCharAt(caretPos);
                                }
                                repaint();
                                return;
                            }
                        }
                    } else {
                        if (e.getKeyCode() == KeyEvent.VK_DELETE && selectedShape != null && editingEnabled) {
                            shapes.remove(selectedShape);
                            selectedShape = null;
                            repaint();
                        }
                    }
                }
            });
        }

        private boolean isCtrl(KeyEvent e) {
            return (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
        }

        private void insertText(String s) {
            if (hasSelection()) {
                int a = Math.min(selStart, selEnd), b = Math.max(selStart, selEnd);
                textBuffer.replace(a, b, s);
                caretPos = a + s.length();
                clearSelection();
            } else {
                textBuffer.insert(caretPos, s);
                caretPos += s.length();
            }
            repaint();
        }

        private void deleteSelection() {
            if (!hasSelection()) return;
            int a = Math.min(selStart, selEnd), b = Math.max(selStart, selEnd);
            textBuffer.delete(a, b);
            caretPos = a;
            clearSelection();
        }

        private boolean hasSelection() {
            return selStart >= 0 && selEnd >= 0 && selStart != selEnd;
        }

        private void clearSelection() {
            selStart = selEnd = -1;
        }

        private void copySelectionOrCaret() {
            String toCopy;
            if (hasSelection()) {
                int a = Math.min(selStart, selEnd), b = Math.max(selStart, selEnd);
                toCopy = textBuffer.substring(a, b);
            } else {
                toCopy = "";
            }
            StringSelection ss = new StringSelection(toCopy);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(ss, null);
        }

        private void pasteClipboard() {
            try {
                java.awt.datatransfer.Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
                if (t != null && t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                    String txt = (String) t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                    if (txt != null) insertText(txt);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        private void moveCaretUpDown(int dir) {
            String s = textBuffer.toString();
            int pos = caretPos;
            int lineStart = s.lastIndexOf('\n', pos - 1);
            int curLineStart = (lineStart == -1) ? 0 : lineStart + 1;
            int col = pos - curLineStart;
            if (dir < 0) {
                if (curLineStart == 0) { caretPos = 0; return; }
                int prevLineEnd = curLineStart - 1;
                int prevLineStart = s.lastIndexOf('\n', prevLineEnd - 1);
                prevLineStart = (prevLineStart == -1) ? 0 : prevLineStart + 1;
                int prevLen = prevLineEnd - prevLineStart + 1;
                caretPos = prevLineStart + Math.min(col, prevLen);
            } else {
                int nextNl = s.indexOf('\n', pos);
                if (nextNl == -1) {
                    caretPos = s.length();
                    return;
                }
                int nextLineStart = nextNl + 1;
                int nextLineEnd = s.indexOf('\n', nextLineStart);
                nextLineEnd = (nextLineEnd == -1) ? s.length() - 1 : nextLineEnd - 1;
                int nextLen = (nextLineEnd - nextLineStart + 1);
                caretPos = nextLineStart + Math.min(col, Math.max(0, nextLen));
            }
        }

        private void startEditingTextShape(TextShape t, Point clickWorld) {
            typing = true;
            textStartWorld = new Point(t.pos);
            textBuffer.setLength(0);
            textBuffer.append(t.text);
            caretPos = caretIndexFromClick(t.text, t.pos, clickWorld);
            clearSelection();
            shapes.remove(t);
            selectedShape = null;
            repaint();
        }

        private int caretIndexFromClick(String text, Point origin, Point clickWorld) {
            Font f = new Font(fontFamily, fontStyle, fontSize);
            FontMetrics fm = getFontMetrics(f);
            int lineHeight = fm.getHeight();
            int relY = clickWorld.y - origin.y;
            int line = Math.max(0, (relY + fm.getAscent()) / lineHeight);
            String[] lines = text.split("\n", -1);
            if (line >= lines.length) {
                return text.length();
            }
            int idx = 0;
            for (int i = 0; i < line; i++) idx += lines[i].length() + 1;
            int relX = clickWorld.x - origin.x;
            int col = 0;
            int accW = 0;
            for (; col < lines[line].length(); col++) {
                int cw = fm.charWidth(lines[line].charAt(col));
                if (accW + cw/2 >= relX) break;
                accW += cw;
            }
            return idx + col;
        }

        private void commitTyping() {
            if (!typing || textStartWorld == null) return;
            String text = textBuffer.toString();
            if (!text.isEmpty()) {
                Font f = new Font(fontFamily, fontStyle, fontSize);
                shapes.add(new TextShape(text, new Point(textStartWorld), textColor, f));
            }
            typing = false;
            textStartWorld = null;
            textBuffer.setLength(0);
            caretPos = 0;
            clearSelection();
            repaint();
        }

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
            for (DrawingShape s : shapes) s.draw(g2);
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
                } else if (selectedShape instanceof ImageShape im) {
                    Rectangle b = im.getBounds();
                    g2.drawRect(b.x, b.y, b.width, b.height);
                    int hs = 8;
                    g2.setColor(Color.WHITE);
                    g2.fillRect(b.x - hs/2, b.y - hs/2, hs, hs);
                    g2.fillRect(b.x + b.width - hs/2, b.y - hs/2, hs, hs);
                    g2.fillRect(b.x + b.width - hs/2, b.y + b.height - hs/2, hs, hs);
                    g2.fillRect(b.x - hs/2, b.y + b.height - hs/2, hs, hs);
                    g2.setColor(new Color(60,120,255,200));
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawRect(b.x - hs/2, b.y - hs/2, hs, hs);
                    g2.drawRect(b.x + b.width - hs/2, b.y - hs/2, hs, hs);
                    g2.drawRect(b.x + b.width - hs/2, b.y + b.height - hs/2, hs, hs);
                    g2.drawRect(b.x - hs/2, b.y + b.height - hs/2, hs, hs);
                }
            }
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
            if (typing && textStartWorld != null) {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(new Font(fontFamily, fontStyle, fontSize));
                FontMetrics fm = g2.getFontMetrics();
                int lineHeight = fm.getHeight();
                String[] lines = textBuffer.toString().split("\n", -1);
                if (hasSelection()) {
                    int selA = Math.min(selStart, selEnd), selB = Math.max(selStart, selEnd);
                    int idx = 0;
                    for (int li = 0; li < lines.length; li++) {
                        String ln = lines[li];
                        int lineLen = ln.length();
                        int lineStart = idx;
                        int lineEnd = idx + lineLen;
                        int sx = Math.max(lineStart, selA);
                        int ex = Math.min(lineEnd, selB);
                        if (sx < ex) {
                            int px1 = textStartWorld.x + fm.stringWidth(lines[li].substring(0, sx - lineStart));
                            int px2 = textStartWorld.x + fm.stringWidth(lines[li].substring(0, ex - lineStart));
                            int y = textStartWorld.y + li * lineHeight - fm.getAscent();
                            g2.setColor(new Color(60,120,255,80));
                            g2.fillRect(px1, y, Math.max(2, px2 - px1), lineHeight);
                        }
                        idx += lineLen + 1;
                    }
                }
                g2.setColor(textColor);
                int y = textStartWorld.y;
                for (int i = 0; i < lines.length; i++) {
                    g2.drawString(lines[i], textStartWorld.x, y);
                    y += lineHeight;
                }
                int caretLine = 0;
                int caretCol = 0;
                {
                    int p = Math.max(0, Math.min(caretPos, textBuffer.length()));
                    String s = textBuffer.toString();
                    int upto = 0;
                    for (int i = 0; i < lines.length; i++) {
                        int lnLen = lines[i].length();
                        if (p <= upto + lnLen) {
                            caretLine = i;
                            caretCol = p - upto;
                            break;
                        }
                        upto += lnLen + 1;
                        if (i == lines.length - 1) {
                            caretLine = lines.length - 1;
                            caretCol = lines[lines.length - 1].length();
                        }
                    }
                }
                int cx = textStartWorld.x + g2.getFontMetrics().stringWidth(lines[Math.max(0, Math.min(caretLine, lines.length - 1))].substring(0, Math.max(0, Math.min(caretCol, lines[Math.max(0, Math.min(caretLine, lines.length - 1))].length()))));
                int cy = textStartWorld.y + caretLine * lineHeight - g2.getFontMetrics().getAscent();
                g2.setColor(Color.BLACK);
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(cx, cy, cx, cy + lineHeight);
            }
            g2.dispose();
        }

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
            } else if (s instanceof ImageShape im) {
                Rectangle bounds = im.getBounds();
                return bounds.contains(p);
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
            String[] lines = text.split("\n", -1);
            int w = 0;
            for (String ln : lines) w = Math.max(w, fm.stringWidth(ln));
            int h = fm.getHeight() * lines.length;
            return new Rectangle(pos.x, pos.y - fm.getAscent(), w, h);
        }

        private int getHandleIndex(ImageShape im, Point p) {
            Rectangle b = im.getBounds();
            int hs = 8;
            Rectangle nw = new Rectangle(b.x - hs/2, b.y - hs/2, hs, hs);
            Rectangle ne = new Rectangle(b.x + b.width - hs/2, b.y - hs/2, hs, hs);
            Rectangle se = new Rectangle(b.x + b.width - hs/2, b.y + b.height - hs/2, hs, hs);
            Rectangle sw = new Rectangle(b.x - hs/2, b.y + b.height - hs/2, hs, hs);
            if (nw.contains(p)) return 0;
            if (ne.contains(p)) return 1;
            if (se.contains(p)) return 2;
            if (sw.contains(p)) return 3;
            return -1;
        }

        static class ImageShape implements DrawingShape, Serializable {
            private static final long serialVersionUID = 1L;
            transient BufferedImage img;
            byte[] imgBytes;
            int x, y, width, height;
            ImageShape(BufferedImage img, int x, int y, int w, int h) {
                setImage(img);
                this.x = x; this.y = y; this.width = w; this.height = h;
            }
            void setImage(BufferedImage im) {
                this.img = im;
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(im, "png", baos);
                    baos.flush();
                    imgBytes = baos.toByteArray();
                    baos.close();
                } catch (Exception ex) {
                    imgBytes = null;
                }
            }
            private void ensureImageLoaded() {
                if (img == null && imgBytes != null) {
                    try {
                        img = ImageIO.read(new ByteArrayInputStream(imgBytes));
                    } catch (IOException ignored) { img = null; }
                }
            }
            Rectangle getBounds() { return new Rectangle(x, y, width, height); }
            void setBounds(Rectangle r) { x = r.x; y = r.y; width = r.width; height = r.height; }
            @Override public void draw(Graphics2D g) {
                ensureImageLoaded();
                if (img == null) {
                    g.setColor(new Color(180,180,180));
                    g.fillRect(x, y, width, height);
                    g.setColor(Color.DARK_GRAY);
                    g.drawRect(x, y, width, height);
                    return;
                }
                g.drawImage(img, x, y, width, height, null);
            }
        }
    }

    final CanvasView cv = new CanvasView();
    private JPanel topStack;
    private JPanel topBar;
    private JPanel dropHost;
    private JPanel colorPanel;
    private JPanel modePanel;
    private JPanel textPanel;
    private Roundheadbutton btnModes, btnColor, btnText;
    private JComponent glass;
    private JPanel visibleDropdown = null;
    private JComponent visibleAnchor = null;
    private int visibleW = 0, visibleH = 0;
    private final File autosaveFile = new File(System.getProperty("user.home"), ".canvas_autosave.cvs");
    private Timer autosaveTimer;

    public canvasex() {
        super("Freeplay");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        try {
            Image icon = Toolkit.getDefaultToolkit().getImage("icon.png");
            setIconImage(icon);
        } catch (Exception ignored) {}
        setLayout(new BorderLayout());
        glass = new JPanel(null);
        glass.setOpaque(false);
        glass.setLayout(null);
        glass.setVisible(false);
        setGlassPane(glass);
        topStack = new JPanel(new BorderLayout());
        topStack.setOpaque(false);
        topStack.setBackground(new Color(255,255,255,0));
        topStack.setBounds(0, 0, getWidth(), 80);
        topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);
        topBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 3, 12));
        topBar.setBackground(new Color(255,255,255,255));
        topStack.add(topBar, BorderLayout.NORTH);
        RectShadowButton clearBtn = new RectShadowButton("New");
        clearBtn.addActionListener(e -> {
            cv.shapes.clear();
            cv.repaint();
            cv.requestFocusInWindow();
        });
        RectShadowButton saveBtn = new RectShadowButton("Save");
        saveBtn.addActionListener(e -> saveProjectToFile());
        RectShadowButton loadBtn = new RectShadowButton("Load");
        loadBtn.addActionListener(e -> loadProjectFromFile());
        JPanel leftBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftBox.setOpaque(false);
        rightBox.setOpaque(false);
        leftBox.add(clearBtn);
        rightBox.add(saveBtn);
        rightBox.add(loadBtn);
        topBar.add(leftBox, BorderLayout.EAST);
        topBar.add(rightBox, BorderLayout.WEST);
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
        dropHost = new JPanel(null) { @Override public boolean isOpaque() { return false; } };
        dropHost.setPreferredSize(new Dimension(10, 0));
        topStack.add(dropHost, BorderLayout.CENTER);
        colorPanel = buildColorPanel();
        modePanel  = buildModePanel();
        textPanel  = buildTextPanel();
        dropHost.add(colorPanel);
        dropHost.add(modePanel);
        dropHost.add(textPanel);
        hideAllDropdowns();
        glass.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (visibleDropdown == null) return;
                Point pInPopup = SwingUtilities.convertPoint(glass, e.getPoint(), visibleDropdown);
                if (!visibleDropdown.contains(pInPopup)) hideAllDropdowns();
            }
        });
        btnModes.addActionListener(e -> toggleDropdownOverlay(modePanel, btnModes, 180, 130));
        btnColor.addActionListener(e -> toggleDropdownOverlay(colorPanel, btnColor, 320,110));
        btnText.addActionListener(e -> toggleDropdownOverlay(textPanel,  btnText,  360, 240));
        JScrollPane scroller = new JScrollPane(cv);
        JLayeredPane layeredPane = new JLayeredPane();
        setContentPane(layeredPane);
        scroller.setBounds(0, 0, getWidth(), getHeight());
        layeredPane.add(scroller, JLayeredPane.DEFAULT_LAYER);
        topStack.setOpaque(false);
        topStack.setBackground(new Color(255,255,255,0));
        topStack.setBounds(0, 0, getWidth(), 80);
        layeredPane.add(topStack, JLayeredPane.PALETTE_LAYER);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                scroller.setBounds(0, 0, getWidth(), getHeight());
                topStack.setBounds(0, 0, getWidth(), 80);
                reanchorVisibleDropdowns();
            }
        });
        KeyStroke ks = KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, "saveProject");
        getRootPane().getActionMap().put("saveProject", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                saveProjectToFile();
            }
        });
        autosaveTimer = new Timer(30_000, e -> {
            saveProjectSilent(autosaveFile);
        });
        autosaveTimer.setRepeats(true);
        autosaveTimer.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                saveProjectSilent(autosaveFile);
                super.windowClosing(e);
            }
        });
    }

    private static ImageIcon loadSvgIcon(String path, int size) {
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            AffineTransform d2d = gd.getDefaultConfiguration().getDefaultTransform();
            double scale = Math.max(1.0, Math.max(d2d.getScaleX(), d2d.getScaleY()));
            float targetW = (float) Math.ceil(size * scale);
            float targetH = (float) Math.ceil(size * scale);
            byte[] svgBytes = Files.readAllBytes(Paths.get(path));
            TranscoderInput input = new TranscoderInput(new ByteArrayInputStream(svgBytes));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            TranscoderOutput output = new TranscoderOutput(out);
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, targetW);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, targetH);
            transcoder.transcode(input, output);
            out.flush();
            BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
            ImageIcon ic = new ImageIcon(img);
            ic.getImage().getGraphics();
            ic.setDescription(String.valueOf(scale));
            return ic;
        } catch (Exception e) {
            return new ImageIcon(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB));
        }
    }

    static class RoundButton extends JButton {
        private boolean hover = false;
        private final ImageIcon ico;
        private final int iconSize;
        RoundButton(String svgPath, int size) {
            this.ico = loadSvgIcon(svgPath, size);
            this.iconSize = size;
            setIcon(ico);
            setPreferredSize(new Dimension(size + 14, size + 14));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth(), h = getHeight();
            Shape pill = new RoundRectangle2D.Double(2, 2, Math.max(0, w - 4), Math.max(0, h - 4), h, h);
            paintSoftShadow(g2, pill, 2, 0.05f);
            g2.setColor(new Color(255,255,255,255));
            g2.fill(pill);
            if (hover) {
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(60,120,255,140));
                g2.draw(pill);
            }
            if (ico != null && ico.getImage() != null) {
                int ix = (w - iconSize) / 2;
                int iy = (h - iconSize) / 2;
                g2.drawImage(ico.getImage(), ix, iy, iconSize, iconSize, null);
            }
            g2.dispose();
        }
        @Override public boolean contains(int x, int y) {
            int r = Math.min(getWidth(), getHeight()) / 2;
            int cx = getWidth()/2, cy = getHeight()/2;
            int dx = x - cx, dy = y - cy;
            return dx*dx + dy*dy <= r*r;
        }
    }

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
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth(), h = getHeight();
            Shape circle = new Ellipse2D.Double(2,2,w-4,h-4);
            paintSoftShadow(g2, circle, 2, 0.05f);
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

    static class Roundheadbutton extends JButton {
        private boolean hover = false;
        private final ImageIcon ico;
        private final int iconSize;
        Roundheadbutton(String svgPath, int size) {
            this.ico = loadSvgIcon(svgPath, size);
            this.iconSize = size;
            setIcon(ico);
            setPreferredSize(new Dimension(size * 2 + 20, size + 18));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setHorizontalAlignment(SwingConstants.CENTER);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth(), h = getHeight();
            Shape pill = new RoundRectangle2D.Double(2, 2, Math.max(0, w - 4), Math.max(0, h - 4), h, h);
            paintSoftShadow(g2, pill, 2, 0.05f);
            g2.setColor(new Color(255,255,255,255));
            g2.fill(pill);
            if (hover) {
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(60,120,255,140));
                g2.draw(pill);
            }
            if (ico != null && ico.getImage() != null) {
                int ix = (w - iconSize) / 2;
                int iy = (h - iconSize) / 2;
                g2.drawImage(ico.getImage(), ix, iy, iconSize, iconSize, null);
            }
            g2.dispose();
        }
        @Override public boolean contains(int x, int y) {
            int w = getWidth(), h = getHeight();
            Shape pill = new RoundRectangle2D.Double(2, 2, Math.max(0, w - 4), Math.max(0, h - 4), h, h);
            return pill.contains(x, y);
        }
    }

    static class RectShadowButton extends JButton {
        private boolean hover = false;
        RectShadowButton(String text) {
            super(text);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(getFont().deriveFont(Font.PLAIN, 13f));
            setMargin(new Insets(6,12,6,12));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            int arc = 12;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int w = getWidth(), h = getHeight();
            Shape rr = new RoundRectangle2D.Double(2, 2, Math.max(0, w-4), Math.max(0, h-4), arc, arc);
            paintSoftShadow(g2, rr, 2, 0.05f);
            g2.setColor(new Color(255,255,255,255));
            g2.fill(rr);
            if (hover) {
                g2.setColor(new Color(240,245,255,180));
                g2.fill(rr);
                g2.setColor(new Color(120,160,255,100));
                g2.setStroke(new BasicStroke(1.2f));
                g2.draw(rr);
            }
            g2.setColor(Color.BLACK);
            FontMetrics fm = g2.getFontMetrics(getFont());
            String txt = getText();
            int tx = (w - fm.stringWidth(txt)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(txt, tx, ty);
            g2.dispose();
        }
        @Override public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.height = Math.max(d.height, 30);
            d.width = Math.max(d.width, 64);
            return d;
        }
    }

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
            paintSoftShadow(g2, rr, 3, 0.05f);
            g2.setColor(new Color(250,250,250, 255));
            g2.fill(rr);
            g2.setColor(new Color(255,255,255,10));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(rr);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static class ShadowSlider extends JSlider {
        ShadowSlider(int min, int max, int val) {
            super(min, max, val);
            setOpaque(false);
            setUI(new BasicSliderUI(this) {
                @Override public void paintTrack(Graphics g) { }
                @Override public void paintThumb(Graphics g) { }
            });
            setFocusable(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
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
            paintSoftShadow(g2, thumb, 2, 0.04f);
            g2.setColor(Color.WHITE);
            g2.fill(thumb);
            g2.setColor(new Color(0,150,150,200));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(thumb);
            g2.dispose();
        }
    }

    private void toggleDropdownOverlay(JPanel panel, JComponent anchorBtn, int prefW, int prefH) {
        boolean willShow = (visibleDropdown != panel);
        hideAllDropdowns();
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

    private JPanel buildColorPanel() {
        RoundedPanel p = new RoundedPanel(20);
        p.setLayout(new BorderLayout(8, 8));
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

    private JPanel buildModePanel() {
        RoundedPanel p = new RoundedPanel(20);
        p.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 10));
        RoundButton penBtn   = new RoundButton("icons/pen.svg", 26);
        RoundButton lineBtn  = new RoundButton("icons/line.svg", 26);
        RoundButton rectBtn  = new RoundButton("icons/rect.svg", 26);
        RoundButton ovalBtn  = new RoundButton("icons/kreis.svg", 26);
        RoundButton textBtn  = new RoundButton("icons/text.svg", 26);
        final RoundButton moveBtn  = new RoundButton("icons/cursor.svg", 26);
        moveBtn.setToolTipText("Verschieben: AUS");
        penBtn.addActionListener(e -> { cv.editingEnabled = false; moveBtn.setBorderPainted(false); moveBtn.setToolTipText("Verschieben: AUS"); cv.mode = CanvasView.Mode.PEN;   cv.typing=false; cv.requestFocusInWindow(); });
        lineBtn.addActionListener(e -> { cv.editingEnabled = false; moveBtn.setBorderPainted(false); moveBtn.setToolTipText("Verschieben: AUS"); cv.mode = CanvasView.Mode.LINE;  cv.typing=false; cv.requestFocusInWindow(); });
        rectBtn.addActionListener(e -> { cv.editingEnabled = false; moveBtn.setBorderPainted(false); moveBtn.setToolTipText("Verschieben: AUS"); cv.mode = CanvasView.Mode.RECT;  cv.typing=false; cv.requestFocusInWindow(); });
        ovalBtn.addActionListener(e -> { cv.editingEnabled = false; moveBtn.setBorderPainted(false); moveBtn.setToolTipText("Verschieben: AUS"); cv.mode = CanvasView.Mode.OVAL;  cv.typing=false; cv.requestFocusInWindow(); });
        textBtn.addActionListener(e -> { cv.editingEnabled = false; moveBtn.setBorderPainted(false); moveBtn.setToolTipText("Verschieben: AUS"); cv.mode = CanvasView.Mode.TEXT;  cv.typing=false; cv.requestFocusInWindow(); });
        moveBtn.addActionListener(e -> {
            cv.editingEnabled = !cv.editingEnabled;
            if (cv.editingEnabled) {
                moveBtn.setToolTipText("Verschieben: AN");
                moveBtn.setBorderPainted(true);
                cv.mode = CanvasView.Mode.NONE;
                cv.typing = false;
            } else {
                moveBtn.setToolTipText("Verschieben: AUS");
                moveBtn.setBorderPainted(false);
                cv.mode = CanvasView.Mode.PEN;
            }
            cv.requestFocusInWindow();
        });
        p.add(moveBtn); p.add(penBtn); p.add(lineBtn); p.add(rectBtn); p.add(ovalBtn); p.add(textBtn);
        p.setSize(300, 120);
        p.setVisible(false);
        return p;
    }

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
        float[] hsb = Color.RGBtoHSB(cv.textColor.getRed(), cv.textColor.getGreen(), cv.textColor.getBlue(), null);
        sh.setValue(Math.round(hsb[0]*360));
        ss.setValue(Math.round(hsb[1]*100));
        sb.setValue(Math.round(hsb[2]*100));
        preview.setBackground(cv.textColor);
        p.setVisible(false);
        return p;
    }

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

    private void saveProjectSilent(File file) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            ProjectData pd = new ProjectData(cv.shapes, cv.scale, cv.offX, cv.offY,
                    cv.penSize, cv.color, cv.textColor,
                    cv.fontSize, cv.fontFamily, cv.fontStyle);
            oos.writeObject(pd);
            oos.flush();
        } catch (Exception ex) {
            System.err.println("Error saving project to " + file.getAbsolutePath() + ": " + ex.getMessage());
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

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new canvasex().setVisible(true));
    }
}