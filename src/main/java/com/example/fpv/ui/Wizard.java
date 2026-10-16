package com.example.fpv.ui;

import com.example.fpv.analysis.AnalysisResult;
import com.example.fpv.analysis.FlightAnalyzer;
import com.example.fpv.blackbox.FlightData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

public final class Wizard {
    private final List<FlightData> flights;
    private final List<FlightAnalyzer> analyzers;
    private final Scanner sc = new Scanner(System.in);

    public Wizard(List<FlightData> flights, List<FlightAnalyzer> analyzers) {
        this.flights = flights;
        this.analyzers = analyzers;
    }

    public void run() {
        banner();
        System.out.println();
        System.out.println("Φορτώθηκαν " + flights.size() + " πτήσεις:");
        printFlightOverview();
        System.out.println();
        while (true) {
            printMenu();
            String in = readLine();
            if (in == null) return;
            String choice = in.trim();
            if (choice.equals("x") || choice.equals("exit") || choice.equals("q")) {
                System.out.println("Αντίο!");
                return;
            }
            if (choice.equals("l")) {
                printFlightOverview();
                continue;
            }
            if (choice.equals("d")) {
                printDetails();
                continue;
            }
            int n;
            try {
                n = Integer.parseInt(choice);
            } catch (NumberFormatException e) {
                System.out.println("  Δεν κατάλαβα. Δώσε αριθμό βήματος, 'l', 'd' ή 'x'.");
                continue;
            }
            if (n < 1 || n > analyzers.size()) {
                System.out.println("  Μη έγκυρο βήμα (1-" + analyzers.size() + ").");
                continue;
            }
            runStep(analyzers.get(n - 1));
        }
    }

    private void banner() {
        System.out.println("===================================================");
        System.out.println("  FPV LOG ANALYZER — προπονητής βηματικού tuning");
        System.out.println("  Βήμα-βήμα σύγκριση πτήσεων και συστάσεις");
        System.out.println("===================================================");
    }

    private void printFlightOverview() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"#", "Αρχείο", "Πτήση", "Firmware/Board", "Διάρκεια", "Frames", "Καθαριότητα"});
        int i = 0;
        for (FlightData f : flights) {
            String dur = f.mainCount > 1
                    ? String.format("%.1f s", (f.timeAt(f.mainCount - 1) - f.timeAt(0)) / 1_000_000.0) : "-";
            double corruptPct = f.stats.corruptFrames * 100.0 / Math.max(1, f.mainCount);
            rows.add(new String[]{
                    String.valueOf(++i),
                    f.sourceName,
                    "S#" + (f.sessionIndex + 1),
                    f.header.board() + " · " + f.header.firmware(),
                    dur,
                    String.valueOf(f.mainCount),
                    corruptPct == 0 ? "οκ" : String.format("%.0f%% corrupt", corruptPct)
            });
        }
        printTable(rows);
    }

    private void printDetails() {
        for (int i = 0; i < flights.size(); i++) {
            FlightData f = flights.get(i);
            System.out.println((i + 1) + ". " + f.sourceName + " (S#" + (f.sessionIndex + 1) + ")");
            System.out.println("   Craft: '" + f.header.craftName() + "'  |  Board: " + f.header.board());
            System.out.println("   Firmware: " + f.header.firmware() + "  |  Data version: " + f.header.dataVersion());
            System.out.println("   Frames I/P/S/G: " + f.mainCount + "/" + 0 + "/" + f.slowCount + "/" + f.gpsCount
                    + "  |  corrupt: " + f.stats.corruptFrames + " (bytes " + f.stats.corruptBytes + ", resyncs " + f.stats.resyncs + ")");
            System.out.println("   End: " + (f.stats.reachedLogEnd ? "κανονικό" : "κόπηκε"));
        }
    }

    private void printMenu() {
        System.out.println("------------------------------------------");
        System.out.println("ΒΗΜΑΤΑ ΑΝΑΛΥΣΗΣ:");
        for (int i = 0; i < analyzers.size(); i++) {
            FlightAnalyzer a = analyzers.get(i);
            System.out.println("  [" + (i + 1) + "] " + a.title());
        }
        System.out.println("  [l] λίστα πτήσεων   [d] λεπτομέρειες   [x] έξοδος");
        System.out.print("Επίλεξε βήμα: ");
    }

    private void runStep(FlightAnalyzer analyzer) {
        System.out.println();
        System.out.println("===== ΒΗΜΑ " + analyzer.id() + ": " + analyzer.title() + " =====");
        System.out.println();
        System.out.println("ΠΡΟΕΤΟΙΜΑΣΙΑ / ΤΙ ΠΡΕΠΕΙ ΝΑ ΚΑΝΕΙΣ:");
        System.out.println(wrap(analyzer.instructions(), 76, "  "));
        System.out.println();

        List<Object[]> results = new ArrayList<>();
        for (int i = 0; i < flights.size(); i++) {
            FlightData f = flights.get(i);
            AnalysisResult r = analyzer.analyze(f);
            results.add(new Object[]{i + 1, f.sourceName, f.sessionIndex, r});
        }

        System.out.println("ΑΠΟΤΕΛΕΣΜΑΤΑ (όσο μεγαλύτερο το score, τόσο καλύτερα):");
        List<String[]> table = new ArrayList<>();
        table.add(new String[]{"#", "Αρχείο", "Πτήση", "Score", "Αξιολόγηση"});
        for (Object[] row : results) {
            FlightData f = flights.get((Integer) row[0] - 1);
            AnalysisResult r = (AnalysisResult) row[3];
            table.add(new String[]{
                    String.valueOf(row[0]),
                    f.sourceName,
                    "S#" + (f.sessionIndex + 1),
                    String.format("%.0f", r.score),
                    shortVerdict(r.verdict)
            });
        }
        printTable(table);

        System.out.println();
        results.sort(Comparator.comparingDouble(o -> ((AnalysisResult) ((Object[]) o)[3]).score).reversed());
        System.out.println("ΣΥΣΤΑΣΗ:");
        for (int rank = 0; rank < results.size(); rank++) {
            Object[] row = results.get(rank);
            AnalysisResult r = (AnalysisResult) row[3];
            FlightData f = flights.get((Integer) row[0] - 1);
            String medal = rank == 0 ? "1η" : rank == 1 ? "2η" : rank == 2 ? "3η" : (rank + 1) + "η";
            System.out.println(String.format("  %s  πτήση #%d  (%s, S#%d)  |  score %.0f  |  %s",
                    medal, row[0], f.sourceName, f.sessionIndex + 1, r.score, r.verdict));
        }
        Object[] best = results.get(0);
        AnalysisResult bestR = (AnalysisResult) best[3];
        System.out.println();
        System.out.println("💡 Βέλτιστη για αυτό το βήμα: ΠΤΗΣΗ #" + best[0]
                + " με σκορ " + String.format("%.0f", bestR.score) + ".");
        if (results.size() > 1) {
            AnalysisResult second = (AnalysisResult) results.get(1)[3];
            if (Math.abs(bestR.score - second.score) < 5) {
                System.out.println("   Προσοχή: οι διαφορές (score) είναι πολύ μικρές — κάνε τη ρύθμιση,");
                System.out.println("   πέτα ξανά τον ίδιο ακριβώς γύρο και σύγκρινε πριν βγάλεις συμπέρασμα.");
            } else {
                System.out.println("   Συνέχισε στο επόμενο βήμα, ή πέτα μία ακόμα δοκιμή με τη ρύθμιση της καλύτερης πτήσης.");
            }
        }
        System.out.println();

        System.out.println("ΕΥΡΗΜΑΤΑ ΑΝΑ ΠΤΗΣΗ:");
        for (Object[] row : results) {
            FlightData f = flights.get((Integer) row[0] - 1);
            AnalysisResult r = (AnalysisResult) row[3];
            System.out.println("  --- Πτήση #" + row[0] + "  [" + f.sourceName + " S#" + (f.sessionIndex + 1) + "] ---");
            for (String line : r.findings) {
                System.out.println("     " + line);
            }
        }
        System.out.println();
        System.out.println("Πάτα Enter για επιστροφή στο μενού...");
        readLine();
    }

    private static String shortVerdict(String verdict) {
        if (verdict.length() <= 44) return verdict;
        return verdict.substring(0, 41) + "...";
    }

    private String readLine() {
        try {
            if (!sc.hasNextLine()) return null;
            String line = sc.nextLine();
            if (line == null) return null;
            return line;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void printTable(List<String[]> rows) {
        int[] width = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int c = 0; c < row.length; c++) {
                if (row[c] != null && row[c].length() > width[c]) width[c] = row[c].length();
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            StringBuilder sb = new StringBuilder("  ");
            String[] row = rows.get(r);
            for (int c = 0; c < row.length; c++) {
                String cell = row[c] == null ? "" : row[c];
                if (c == row.length - 1) {
                    sb.append(cell);
                } else {
                    sb.append(String.format("%-" + (width[c] + 2) + "s", cell));
                }
            }
            System.out.println(sb);
            if (r == 0) {
                StringBuilder sep = new StringBuilder("  ");
                for (int c = 0; c < row.length; c++) {
                    sep.append("-".repeat(c == row.length - 1 ? width[c] : width[c] + 2));
                }
                System.out.println(sep);
            }
        }
    }

    private static String wrap(String text, int width, String indent) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            StringBuilder cur = new StringBuilder(indent);
            for (String word : line.split(" ")) {
                if (cur.length() > indent.length() && cur.length() + word.length() + 1 > width) {
                    out.append(cur).append('\n');
                    cur = new StringBuilder(indent);
                }
                if (cur.length() > indent.length()) cur.append(' ');
                cur.append(word);
            }
            out.append(cur).append('\n');
        }
        return out.toString();
    }
}