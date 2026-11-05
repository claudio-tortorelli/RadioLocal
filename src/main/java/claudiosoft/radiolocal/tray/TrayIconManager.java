package claudiosoft.radiolocal.tray;

import claudiosoft.commons.BasicLogger;
import claudiosoft.radiolocal.RadioLocal;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import javax.imageio.ImageIO;

/**
 * Wrapper minimalista attorno a {@link java.awt.TrayIcon}.
 *
 * <p>
 * Gestisce:
 * <ul>
 * <li>creazione e aggiunta della icona al SystemTray</li>
 * <li>visualizzazione dei balloon (notifiche)</li>
 * <li>aggiornamento del tooltip</li>
 * <li>rimozione pulita all shutdown</li>
 * </ul>
 *
 * Tutte le interazioni con AWT avvengono tramite
 * {@link EventQueue#invokeLater}, quindi sono thread‑safe anche quando chiamate
 * dal thread di playback.
 */
public class TrayIconManager {

    private static final BasicLogger LOG = RadioLocal.logger;

    private final TrayIcon trayIcon;      // null se il platform non lo supporta
    private final SystemTray systemTray;  // null se non supportato
    private final PopupMenu popupMenu;        // <-- salvata per aggiungere voci dinamiche

    /**
     * Costruttore: cerca "/icon.png" sul class‑path.
     */
    public TrayIconManager() {
        TrayIcon ti = null;
        SystemTray st = null;
        PopupMenu popup = null;

        if (!SystemTray.isSupported()) {
            LOG.warn("SystemTray non supportato su questa piattaforma – TrayIconManager è inattivo");
            this.trayIcon = null;
            this.systemTray = null;
            this.popupMenu = null;
            return;
        }

        try {
            st = SystemTray.getSystemTray();

            // 1) icona
            Image img = loadIcon("/icon.png");
            if (img == null) {
                img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                LOG.warn("Icona di default non trovata – uso immagine vuota");
            }

            // 2) TrayIcon vera e propria
            ti = new TrayIcon(img);
            ti.setImageAutoSize(true);

            // 3) popup menu (con voce “Esci” di base)
            popup = new PopupMenu();
            MenuItem exitItem = new MenuItem("Esci");
            exitItem.addActionListener(e -> {
                LOG.info("Richiesta di chiusura dalla TrayIcon");
                System.exit(0);
            });
            popup.add(exitItem);
            ti.setPopupMenu(popup);

            // 4) aggiunta al system tray
            st.add(ti);
            LOG.info("TrayIcon aggiunta al SystemTray");
        } catch (Exception e) {
            LOG.error("Errore nella creazione della TrayIcon", e);
            ti = null;
            st = null;
            popup = null;
        }

        this.trayIcon = ti;
        this.systemTray = st;
        this.popupMenu = popup;
    }

    /**
     * Carica un'immagine dal class‑path (ritorna null se fallisce).
     */
    private Image loadIcon(String path) {
        URL url = getClass().getResource(path);
        if (url == null) {
            LOG.warn("Risorsa icona non trovata: %s".formatted(path));
            return null;
        }
        try {
            return ImageIO.read(url);
        } catch (IOException e) {
            LOG.error("Impossibile leggere l'icona %s".formatted(path), e);
            return null;
        }
    }

    /**
     * Aggiorna il tooltip della icona (eseguito sul thread EDT).
     */
    public void setTooltip(final String tooltip) {
        if (trayIcon != null) {
            EventQueue.invokeLater(() -> trayIcon.setToolTip(tooltip));
        } else {
            LOG.warn("setTooltip() ignorato: TrayIcon non disponibile");
        }
    }

    /**
     * Mostra un balloon (notifica) sopra la tray.
     *
     * @param caption titolo (max ~64 caratteri)
     * @param text corpo del messaggio
     * @param messageType tipo di icona (INFO, WARNING, ERROR, NONE)
     */
    public void displayMessage(final String caption,
            final String text,
            final TrayIcon.MessageType messageType) {

        if (trayIcon == null) {
            LOG.warn("displayMessage() ignorato perché la TrayIcon non è stata creata");
            return;
        }
        EventQueue.invokeLater(() -> trayIcon.displayMessage(caption, text, messageType));
    }

    /**
     * Rimuove l’icona dal SystemTray (usato al termine dell’app).
     */
    public void remove() {
        if (systemTray != null && trayIcon != null) {
            systemTray.remove(trayIcon);
            LOG.info("TrayIcon rimossa dal SystemTray");
        }
    }

    /**
     * Aggiunge una voce al popup‑menu della tray‑icon.
     *
     * @param label testo della voce
     * @param listener azione da eseguire
     */
    public void addMenuItem(String label, ActionListener listener) {
        if (popupMenu != null) {
            MenuItem item = new MenuItem(label);
            item.addActionListener(listener);
            popupMenu.add(item);
            LOG.debug("Aggiunta voce menu tray: " + label);
        } else {
            LOG.warn("Impossibile aggiungere voce menu: popup non disponibile");
        }
    }
}
