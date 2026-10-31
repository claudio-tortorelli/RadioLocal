package claudiosoft.radiolocal.ui;

import claudiosoft.radiolocal.model.Library;
import claudiosoft.radiolocal.model.Track;
import claudiosoft.radiolocal.service.RadioStationService;
import java.awt.*;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.swing.*;

/**
 * Finestra Swing per inserire una richiesta di brano.
 *
 * • Se l’utente sceglie un artista → compare una combo con tutti i titoli di
 * quell’artista. • Altrimenti il campo di testo è usato per cercare per titolo
 * (parziale). • Quando l’utente conferma la traccia trovata viene inserita
 * nella coda del {@link RadioStationService}.
 */
public class RequestDialog extends JDialog {

    private final Library library;
    private final RadioStationService service;

    private final JComboBox<String> artistCombo;
    private final JTextField titleField;
    private final JComboBox<Track> trackCombo;
    private final JButton okBtn;
    private final JButton cancelBtn;

    public RequestDialog(Library lib, RadioStationService svc) {
        super((Frame) null, "Richiesta traccia", true);
        this.library = lib;
        this.service = svc;

        // ---------- Layout ----------
        setLayout(new BorderLayout(10, 10));
        JPanel main = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Artista
        gbc.gridx = 0;
        gbc.gridy = 0;
        main.add(new JLabel("Artista:"), gbc);
        gbc.gridx = 1;
        artistCombo = new JComboBox<>();
        artistCombo.addItem("[seleziona artista]");
        library.getArtistNames().forEach(artistCombo::addItem);
        main.add(artistCombo, gbc);

        // Titolo (campo di testo)
        gbc.gridx = 0;
        gbc.gridy = 1;
        main.add(new JLabel("Titolo:"), gbc);
        gbc.gridx = 1;
        titleField = new JTextField(20);
        main.add(titleField, gbc);

        // Titolo (combo di tracce) – inizialmente nascosta
        gbc.gridx = 0;
        gbc.gridy = 2;
        main.add(new JLabel("Traccia:"), gbc);
        gbc.gridx = 1;
        trackCombo = new JComboBox<>();
        trackCombo.setVisible(false);
        // renderer per mostrare solo il titolo
        trackCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list,
                    Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Track t) {
                    setText(t.getTitle());
                }
                return this;
            }
        });
        main.add(trackCombo, gbc);

        // Bottoni OK / Annulla
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okBtn = new JButton("OK");
        cancelBtn = new JButton("Annulla");
        btnPanel.add(okBtn);
        btnPanel.add(cancelBtn);

        add(main, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(null); // centro schermo

        // ---------- Listener ----------
        artistCombo.addActionListener(e -> onArtistChanged());
        okBtn.addActionListener(e -> onOk());
        cancelBtn.addActionListener(e -> dispose());

        // Predefinisce lo stato UI iniziale
        onArtistChanged();
    }

    /**
     * Gestisce la commutazione tra “cerca per titolo” e “cerca per artista”.
     */
    private void onArtistChanged() {
        String sel = (String) artistCombo.getSelectedItem();
        boolean hasArtist = sel != null && !sel.equals("[seleziona artista]");

        if (hasArtist) {
            // Mostra le tracce dell’artista selezionato
            List<Track> tracks = library.getTracksByArtist(sel);
            trackCombo.removeAllItems();
            for (Track t : tracks) {
                trackCombo.addItem(t);
            }
            titleField.setVisible(false);
            trackCombo.setVisible(true);
        } else {
            // Modalità ricerca per titolo
            titleField.setVisible(true);
            trackCombo.setVisible(false);
        }
        pack(); // ridimensiona per mostrare/nascondere componenti
    }

    /**
     * Conferma della richiesta
     */
    private void onOk() {
        // 1) Richiesta per artista → traccia selezionata
        if (trackCombo.isVisible()) {
            Track chosen = (Track) trackCombo.getSelectedItem();
            if (chosen != null) {
                service.addRequestedTrack(chosen);
                dispose();
                return;
            }
            JOptionPane.showMessageDialog(this,
                    "Seleziona una traccia.", "Errore", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 2) Ricerca per titolo (parziale)
        String query = titleField.getText().trim();
        if (query.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Inserisci almeno parte del titolo.", "Errore", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<Track> matches = library.searchTracksByTitle(query);
        if (matches.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Nessun brano trovato per '" + query + "'.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // scegli un risultato a caso
        Track random = matches.get(ThreadLocalRandom.current()
                .nextInt(matches.size()));
        service.addRequestedTrack(random);
        dispose();
    }
}
