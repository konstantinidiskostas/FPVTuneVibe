package com.example.fpv.analysis;

import java.util.ArrayList;
import java.util.List;

public final class Guide {
    public static final String TYPE_INFO = "info";
    public static final String TYPE_ANALYZE = "analyze";

    public record Step(String id, String phase, String title, String type,
                       String instructions, String url, String analyzer) {
        public boolean isAnalyze() {
            return TYPE_ANALYZE.equals(type);
        }
    }

    public record Section(String id, String title, String home, List<Step> steps) {
    }

    public record Profile(String droneClass, String style, int cells, String prop, String bfVersion,
                          String focus, String motorKv, String propBlades, String escFirmware,
                          String fcMounting) {
        public Profile(String droneClass, String style, int cells, String prop, String bfVersion) {
            this(droneClass, style, cells, prop, bfVersion, "general", "", "", "", "");
        }

        public static Profile defaults() {
            return new Profile("5inch", "freestyle", 4, "", "");
        }

        public boolean longRange() {
            return "longrange".equals(style) || "7inch".equals(droneClass) || "cine".equals(droneClass);
        }

        public boolean racing() {
            return "racing".equals(style);
        }

        public boolean whoop() {
            return "whoop".equals(droneClass);
        }

        public boolean bigProp() {
            return "7inch".equals(droneClass) || "cine".equals(droneClass);
        }

        public boolean focusLatency() {
            return "latency".equals(focus) || racing();
        }

        public boolean focusNoise() {
            return "noise".equals(focus) || longRange();
        }

        public boolean focusPerf() {
            return "performance".equals(focus);
        }

        /** Whether the ESC/firmware can run the RPM filter (bidirectional DShot). */
        public boolean rpmCapable() {
            String e = escFirmware == null ? "" : escFirmware;
            return switch (e) {
                case "blheli_s" -> false;
                case "blheli_32", "bluejay", "am32" -> true;
                default -> true;
            };
        }

        public int kv() {
            try {
                return Integer.parseInt(motorKv.trim());
            } catch (Exception e) {
                return 0;
            }
        }

        public String classDesc() {
            return switch (droneClass == null ? "" : droneClass) {
                case "whoop" -> "Micro ducted drone — ασφαλές για indoor και κοντά σε κόσμο. "
                        + "Χαμηλή αδράνεια, εύκολα ζεσταίνεται, propwash.";
                case "toothpick" -> "Ελαφρύ outdoor drone — σαν 5\" αλλά πιο ήσυχο. Λίγη μάζα, νιώθει τον αέρα.";
                case "7inch" -> "Long-range cruiser — αποδοτικό, για 15-30 λεπτά πτήση. Μεγάλη αδράνεια.";
                case "cine" -> "Cine-lifter — σηκώνει κάμερα (RED/BMPCC). Πολύ βάρος, θέλει προσοχή στο tuning.";
                default -> "Βασικό 5\" — ισορροπία δύναμης, ευελιξίας και βάρους.";
            };
        }

        public String label() {
            String dc = switch (droneClass == null ? "" : droneClass) {
                case "whoop" -> "tiny whoop";
                case "toothpick" -> "toothpick / cinewhoop";
                case "7inch" -> "7\"+ long range";
                case "cine" -> "cine-lifter";
                default -> "5\"";
            };
            String st = switch (style == null ? "" : style) {
                case "racing" -> "αγωνιστικό";
                case "cinematic" -> "cinematic";
                case "longrange" -> "long range";
                case "cruise" -> "cruising";
                default -> "freestyle";
            };
            String fo = switch (focus == null ? "" : focus) {
                case "latency" -> " · focus: latency";
                case "noise" -> " · focus: noise";
                case "performance" -> " · focus: performance";
                default -> "";
            };
            String c = cells > 0 ? cells + "S" : "";
            return (dc + " · " + st + (c.isEmpty() ? "" : " · " + c) + fo).trim();
        }
    }

    private Guide() {
    }

    private static final String PID = "https://fpv-drone.info/en/guides/pid-tuning";
    private static final String FILTER = "https://fpv-drone.info/en/guides/filter-tuning";
    private static final String BF_PID = "https://betaflight.com/docs/wiki/guides/current/PID-Tuning-Guide";
    private static final String OSCAR = "https://oscarliang.com/pid/";
    private static final String OSCAR_TUNING = "https://oscarliang.com/fpv-drone-tuning/";
    private static final String BF_TUNING41 = "https://betaflight.com/docs/wiki/tuning/4-1-Tuning-Notes";
    private static final String FPV_SETUP = "https://fpv-drone.info/en/guides/filter-tuning/flying";

    public static List<Section> sections() {
        return sections(Profile.defaults());
    }

    public static List<Section> sections(Profile p) {
        if (p == null) p = Profile.defaults();
        List<Step> steps = new ArrayList<>();

        steps.addAll(setup(p));
        steps.addAll(filters(p));
        steps.addAll(pids(p));
        steps.addAll(finishing(p));

        return List.of(new Section("guide", "Οδηγός Tuning", PID, steps));
    }

    private static List<Step> setup(Profile p) {
        List<Step> s = new ArrayList<>();
        s.add(info("0", "Φάση Α · Προετοιμασία", "Προετοιμασία (μηχανικά → firmware → baseline)",
                "── 1) Μηχανικός έλεγχος (πριν από ΟΛΑ) ──\n"
                        + "Tuning πάνω σε προβληματικό build είναι χάσιμο χρόνου: μια στραβή έλικα ή ένα loose "
                        + "stack δίνει vibration που ΚΑΝΕΝΑ PID/filter δεν διορθώνει.\n"
                        + "• Σφίξε όλες τις βίδες· κανένα arm να μη λυγίζει, καμία ρωγμή, κανένα loose standoff.\n"
                        + "• Στρίψε τα motor bells με το χέρι: καθόλου grit / play / notchiness. Στραβός άξονας "
                        + "ή βεντούζα = αλλαγή κινητήρα.\n"
                        + "• Φρέσκες, ίσιες, ισορροπημένες έλικες — ΠΟΤΕ μην τιουνάρεις με έλικες από crash. "
                        + "Μία chipped blade μοιάζει ακριβώς με PID oscillation στο log.\n"
                        + "• FC soft-mounted (gummies)· καθόλου floppy καλώδια/κεραίες κοντά στο gyro.\n"
                        + "• Low-ESR capacitor στο ESC power.\n"
                        + "• CG στο κέντρο (μετακίνησε τη μπαταρία ώστε το βάρος να είναι στο σταυρό).\n"
                        + "• Φρέσκια, υγιής μπαταρία. Πέτα με ό,τι πετάς κανονικά (GoPro, ίδια έλικα).\n"
                        + "Αν κάτι δεν είναι υγιές, ΔΙΟΡΘΩΣΕ ΤΟ πρώτα.\n\n"
                        + "── 2) Στόχος & αρχή (balanced tuning) ──\n"
                        + "Στόχος: ένα ΙΣΟΡΡΟΠΗΜΕΝΟ tuning — γρήγορη, σταθερή απόκριση στα sticks χωρίς wobble, "
                        + "με δροσερούς κινητήρες και καθαρό σήμα. Ισορροπία, όχι ακραίες τιμές.\n"
                        + "Σειρά: 1) μηχανικά → 2) φίλτρα → 3) PID → 4) ολοκλήρωση (TPA, dynamic idle).\n"
                        + "Θέλεις εξατομίκευση; Στην καρτέλα «AI Tuning» δίνεις τα στοιχεία του quad και η "
                        + "αναφορά AI προτείνει τιμές ειδικά για αυτό.\n\n"
                        + "── 3) Firmware, protocol & RPM filter ──\n"
                        + "• Τελευταίο Betaflight + Configurator, Expert Mode ON.\n"
                        + "• Flash καθαρό firmware και ΑΣΕ PID/filters στα defaults (ή preset για το μέγεθός σου).\n"
                        + "• Motor protocol ταιριαστό με το loop rate: DShot600 @ 8k, DShot300 @ 4k/3.2k.\n"
                        + "• Στο Motors tab, με μπαταρία, error rate 0%.\n"
                        + "• motor_poles = μαγνητικοί πόλοι κινητήρα (συνήθως 14).\n"
                        + "• Ενεργοποίησε Bidirectional DShot → RPM filter.\n"
                        + rpmFilterNote(p) + "\n\n"
                        + "── 4) Blackbox & ρυθμός logging ──\n"
                        + "• CLI: `set debug_mode = GYRO_SCALED` (BF 4.5+ από default).\n"
                        + "• Logging rate ≥ 1 kHz (ιδανικά 2 kHz), σταθερό loop time.\n"
                        + "• Άδειασε το blackbox flash πριν από κάθε νέα σειρά δοκιμών.\n"
                        + "• Με SD: χαμήλωσε λίγο το gyro logging rate για να μη γίνεται drop.\n"
                        + "ΣΤΟΧΟΙ (noise): Gyro Noise RMS <3 καλό · 3-8 μέτριο · >8 πρόβλημα · "
                        + "D-Term Noise RMS <5 καλό · 5-15 μέτριο · >15 (hot motors).\n\n"
                        + "── 5) Acro mode, backup & καθαρή μέτρηση ──\n"
                        + "• Πέτα σε ACRO (rate) mode — ποτέ angle/horizon: το auto-level «κρύβει» λάθος tuning.\n"
                        + "• Backup (`diff all`) πριν αλλάξεις οτιδήποτε.\n"
                        + "• Για καθαρή αρχική μέτρηση κράτα OFF: Anti-Gravity, Throttle Boost, FF Boost/Transition, "
                        + "Dynamic Idle (προσωρινά), GPS Rescue. Κράτα ON βασικά φίλτρα + RPM filter.\n\n"
                        + "── 6) Baseline flight (σημείο αναφοράς) ──\n"
                        + "Ένα baseline flight σε defaults (~1 pack): ήρεμο hover, μερικά flicks, απότομα "
                        + "punch-outs. Μην διορθώσεις τίποτα — κράτα το log ως reference.",
                OSCAR_TUNING));
        return s;
    }

    private static List<Step> filters(Profile p) {
        List<Step> s = new ArrayList<>();
        String harm = switch (p.droneClass() == null ? "" : p.droneClass()) {
            case "whoop" -> "3 (μικροί κινητήρες)";
            case "7inch", "cine" -> "2 (συντηρητικά, λιγότερο φιλτράρισμα καθυστέρησης)";
            default -> "3";
        };
        s.add(info("1.1", "Φάση Β · Φίλτρα", "Στρατηγική φιλτραρίσματος",
                "Ο στόχος: το ΕΛΑΧΙΣΤΟ φιλτράρισμα που κρατά θόρυβο και κινητήρες υπό έλεγχο — κάθε φίλτρο "
                        + "προσθέτει καθυστέρηση (lag) και χειροτερεύει το propwash.\n\n"
                        + "Σειρά (σημαντικό!):\n"
                        + "1) RPM filter — σκοτώνει τον θόρυβο των κινητήρων (το πιο «δωρεάν»).\n"
                        + "2) Dynamic Notch — frame resonance που μένει.\n"
                        + "3) Gyro Lowpass — ambient noise floor (τελευταίο, ελάχιστο).\n"
                        + "4) D-term Lowpass — ΥΠΟΧΡΕΩΤΙΚΟ, το D ενισχύει τον θόρυβο 10-100x.\n\n"
                        + "Πού κοιτάς στο log (προσοχή: ΜΗΝ φιλτράρεις κάτω από 100 Hz — εκεί είναι πραγματική "
                        + "κίνηση, φιλτράρισμα = lag):\n"
                        + "  <20 Hz      κίνηση του drone\n"
                        + "  20-100 Hz   propwash / oscillation\n"
                        + "  100-250 Hz  frame resonance / loose parts\n"
                        + "  >250 Hz     motor/prop noise + harmonics\n\n"
                        + "Κανόνας ασφαλείας: μετά από ΚΑΘΕ αλλαγή, πέτα 20-30s aggressive, προσγειώσου και "
                        + "άγγιξε τους κινητήρες — ζεστοί = OK, καυτοί = STOP και πίσω ένα βήμα.",
                FILTER));
        s.add(info("1.2", "Φάση Β · Φίλτρα", "Throttle sweep – πέτα & log",
                "Κάνε το «sampling flight» για τα φίλτρα:\n"
                        + "• Σηκώσου και hover με ελάχιστες εντολές.\n"
                        + "• Ανέβασε throttle ΟΜΑΛΑ 0 → 100% σε 5-10 s, χωρίς άλλα stick inputs (να φανούν τα "
                        + "motor harmonics και οι frame resonances σε όλο το εύρος).\n"
                        + "• Επανάλαβε 2-3 sweeps, μετά προσγειώσου και disarm. Μην χτυπήσεις κάτι.\n"
                        + "• Κάνε τα όλα σε ένα batch (ίδιο flash) — 20s-1min φτάνει.",
                FILTER + "/flying"));
        s.add(info("1.3", "Φάση Β · Φίλτρα", "Καθάρισε & εξήγαγε",
                "Σβήσε το blackbox πριν από κάθε νέα σειρά δοκιμών, μετά κατέβασε το log (Mass Storage) "
                        + "και κράτα το btfl_all.bbl με όλα τα sessions.",
                FILTER + "/extract-logfile"));
        s.add(analyze("1.4", "Φάση Β · Φίλτρα", "Θόρυβος ανά throttle – Ανάλυση",
                "Άνοιξε το heat map (συχνότητα × throttle). Τι κοιτάς:\n"
                        + "• Οριζόντια φωτεινή γραμμή κοντά στο 0 Hz = κίνηση του drone (φυσιολογικό).\n"
                        + "• Οριζόντια γραμμή ~200 Hz που ΔΕΝ αλλάζει με το throttle = frame resonance "
                        + "(σταθερή συχνότητα).\n"
                        + "• Διαγώνια γραμμή που ανεβαίνει με το γκάζι = motor noise fundamental· τα "
                        + "πολλαπλάσιά της = harmonics.\n"
                        + "• Κάθετες φωτεινές μπάρες που δεν κινούνται = σταθερή resonance / ηλεκτρικός θόρυβος.\n\n"
                        + "Ιδανικά (>100 Hz) ο θόρυβος μένει επίπεδος και χαμηλός μετά το RPM filter.",
                FILTER + "/rpm-filters-analysis", "spectro"));
        s.add(analyze("1.5", "Φάση Β · Φίλτρα", "Φάσμα / RPM harmonics – Ανάλυση",
                "Κοίτα το φάσμα του ΑΦΙΛΤΡΑΡΙΣΤΟΥ gyro (Gyro_prefilt / GYRO_SCALED). Μέτρησε:\n"
                        + "• Πόσες διαγώνιες γραμμές (harmonics) βλέπεις — αυτό είναι το harmonics number "
                        + "(συνήθως " + harm + ").\n"
                        + "• Τη χαμηλότερη συχνότητα όπου ξεκινά η πιο αριστερή γραμμή — αυτό είναι το "
                        + "RPM filter min frequency.\n"
                        + "• Τις οριζόντιες γραμμές (frame resonance) — πάνε στο Dynamic Notch.\n"
                        + "Αν δεν φαίνονται καθαρά, ξαναπέτα με ομαλά sweeps.",
                FILTER + "/rpm-filters-analysis", "filter"));
        s.add(info("1.6", "Φάση Β · Φίλτρα", "RPM filter – Config",
                "Ενεργοποίησε/ρύθμισε το RPM filter (προϋπόθεση: bidirectional DShot + σωστό motor_poles):\n\n"
                        + "set dshot_bidir = ON\n"
                        + "set motor_poles = 14          # μέτρα τους μαγνήτες, μην μαντεύεις\n"
                        + "set rpm_filter_harmonics = 3  # πόσες διαγώνιες μέτρησες\n"
                        + "set rpm_filter_q = 500        # 600-700 αν είσαι καθαρός· >750 όχι\n"
                        + "set rpm_filter_min_hz = 100   # χαμηλότερη συχνότητα της γραμμής\n"
                        + "set rpm_filter_fade_range_hz = 50\n\n"
                        + "Πρότεινε harmonics " + harm + ". Κράτα το RPM filter ON — "
                        + "μετά μπορείς να ανεβάσεις τα lowpass cutoffs.\n\n"
                        + rpmFilterNote(p),
                FILTER + "/rpm-filters-config"));
        s.add(info("1.7", "Φάση Β · Φίλτρα", "Dynamic Notch",
                (p.bigProp()
                        ? "Για " + p.label() + " οι frame resonances είναι χαμηλές (<150 Hz): κράτα το "
                                + "dynamic notch πιο χαμηλά και συντηρητικό.\n\n"
                        : "Με ενεργό RPM filter το dynamic notch κυνηγά μόνο τα υπολείμματα (συνήθως frame "
                                + "resonance).\n\n")
                        + "Τιμές (Betaflight 4.2+/4.3):\n"
                        + "set dyn_notch_count = 1      # 4.2+ (με RPM αρκεί 1)\n"
                        + "set dyn_notch_q = 500        # στενή αιχμή· ευρεία/diffuse band → 120-150\n"
                        + "set dyn_notch_min_hz = ...   # ~10% κάτω από τη resonance\n"
                        + "set dyn_notch_max_hz = 350-450  # default OK\n\n"
                        + "Στο 4.3 (SDFT multi-notch): Q 300-450, min_hz όσο πιο ψηλά γίνεται (π.χ. 200), "
                        + "μόνο 1-2 notches. Αν η resonance είναι σε μία σταθερή συχνότητα, ένα STATIC notch "
                        + "μπορεί να είναι καλύτερο από το dynamic.",
                FILTER + "/dynamic-notch-filter"));
        s.add(info("1.8", "Φάση Β · Φίλτρα", "Gyro Lowpass",
                "Το τελευταίο φίλτρο στο gyro — θέλεις το ελάχιστο δυνατό.\n\n"
                        + "• Gyro LPF1 (dynamic 200-500 Hz): με RPM + καθαρό build στο 4.3 μπορεί συχνά να "
                        + "απενεργοποιηθεί (cutoff 0).\n"
                        + "• Gyro LPF2 (static): anti-aliasing όταν PID loop < gyro loop. 4k/8k → κράτα το "
                        + "(500-1000 Hz)· 2k → 500 Hz υποχρεωτικό· 8k/8k → μπορεί off.\n"
                        + "• Τύπος PT1 (default). Στο 4.3 χρησιμοποίησε PT2 αντί Biquad (το Biquad δεν "
                        + "συνιστάται για gyro).\n\n"
                        + "Δούλεψε με το «Gyro Filter Multiplier» slider: δεξιά = λιγότερο φιλτράρισμα / "
                        + "λιγότερο lag. Μικρά βήματα (~10-20%) και έλεγχος θερμοκρασίας.",
                FILTER + "/gyro-filters"));
        s.add(info("1.9", "Φάση Β · Φίλτρα", "D-term Lowpass",
                "ΥΠΟΧΡΕΩΤΙΚΟ — το D είναι το πιο ευαίσθητο στον θόρυβο (τον ενισχύει 10-100x) και το μόνο "
                        + "term που «καίει» κινητήρες.\n\n"
                        + "• D LPF1 (dynamic 70-170 Hz): πιο χαμηλά = πιο δροσεροί κινητήρες, πιο ψηλά = "
                        + "λιγότερο lag.\n"
                        + "• D LPF2 (static ~150 Hz): χρειάζεσαι τουλάχιστον 2 PT1 (ή ένα 2ης τάξης).\n"
                        + "• PT1 είναι το minimum. Στο 4.3 το Biquad παραμένει διαθέσιμο για το D (πιο "
                        + "δυνατό κόψιμο).\n\n"
                        + (p.focusLatency()
                                ? "Focus latency/racing: ανέβασε το D cutoff όσο αντέχει ο θόρυβος, με "
                                        + "συνεχή έλεγχο θερμοκρασίας."
                                : "Προτίμησε λίγο πιο ήπιο φιλτράρισμα στο D.\n"
                                        + "D-Term LPF1 (racing / balanced / cine):  150-200 / 100-150 / 80-120 Hz\n"
                                        + "D-Term LPF2 (racing / balanced / cine):  250-350 / 150-250 / 120-200 Hz")
                        + "\n\nΤο «D Term Filter Multiplier» slider: αριστερά = περισσότερο φιλτράρισμα. "
                        + "ΔΕΞΙΑ ΔΕΝ συνιστάται (grinding/overheat).",
                FILTER + "/d-term-filter"));
        s.add(info("1.10", "Φάση Β · Φίλτρα", "Θερμοκρασίες & μείωση φιλτραρίσματος",
                "Επανάλαβε μέχρι να μη μπορείς να μειώσεις άλλο με ασφάλεια:\n"
                        + "• Αν οι κινητήρες είναι ΔΡΟΣΕΡΟΙ: ανέβασε τα gyro/D cutoffs κατά ~10-20% (slider "
                        + "δεξιά) και ξαναπέτα.\n"
                        + "• Αν είναι ΚΑΥΤΟΙ ή ακούς grinding: κατέβασέ τα ένα βήμα (ή πρόσθεσε gyro LPF).\n"
                        + "• Στόχος: καθαρό φάσμα πάνω από 100 Hz χωρίς υπερβολικό φιλτράρισμα. Μην κυνηγάς "
                        + "τέλειο γράφημα — προτεραιότητα σε δροσερούς κινητήρες και προβλέψιμη πτήση.",
                OSCAR_TUNING));
        return s;
    }

    private static List<Step> pids(Profile p) {
        List<Step> s = new ArrayList<>();
        s.add(info("2.1", "Φάση Γ · PID", "PD Balance – Setup",
                "Θα ακολουθήσουμε τη ροή 3 βημάτων των Betaflight 4.5 / PIDtoolbox: "
                        + "1) PD balance → 2) Master multiplier → 3) PI balance (I).\n\n"
                        + "Στο PID tab (Expert mode) κλείσε ό,τι «θολώνει» το P/D:\n"
                        + "• Stick Response (Feedforward) → 0\n"
                        + "• Dynamic Damping (D Max) → 0\n"
                        + "• Drift-Wobble (I) → χαμηλό (~0.2)\n\n"
                        + "Ονόματα sliders (CLI) — όλα στο 1.0 = στοκ ρύθμιση:\n"
                        + "Master simplified_master_multiplier · Damping simplified_d_gain · "
                        + "Tracking simplified_pi_gain · I simplified_i_gain · "
                        + "Dynamic Damping simplified_dmax_gain (2025.12+: simplified_d_max_gain) · "
                        + "Stick Response simplified_feedforward_gain · Pitch Damping simplified_pitch_d_gain · "
                        + "Pitch Tracking simplified_pitch_pi_gain\n\n"
                        + "Αν έχεις Simplified PID Tuning ενεργό, άλλαζε ΜΟΝΟ sliders και μετά "
                        + "`simplified_tuning apply` — μην πειράζεις raw P/I/D (θα σβηστούν). Αν είσαι σε "
                        + "raw mode (simplified_pids_mode = OFF), άλλαξε p_roll/p_pitch/d_roll/d_pitch απευθείας.\n\n"
                        + "Reference (5\" baseline — προσάρμοσε ανάλογα με το build):\n"
                        + pidTable(),
                PID + "/p-and-i-gains-setup-betaflight"));
        s.add(info("2.2", "Φάση Γ · PID", "PD Balance – Πέτα & log",
                "Κατέβασε το Damping slider στο 0.6 (ασφαλές σημείο για 5\") και ανέβαινε +0.2 κάθε φορά: "
                        + "0.6 → 0.8 → 1.0 → 1.2 → 1.4 → 1.6. Σε κάθε τιμή πέτα ~20s με απότομα roll/pitch "
                        + "(flicks, γρήγορα ημικύκλια) και log. Όταν το D είναι πολύ ψηλό οι κινητήρες "
                        + "ακούγονται «τραχείς» — σταμάτα εκεί. Όλα τα sessions στο ίδιο flash.\n\n"
                        + "Σημείωση: αντί για το Damping μπορείς να σαρώσεις το Tracking (P&I) — και τα δύο "
                        + "αλλάζουν τον λόγο P:D. Πιο καθαρό είναι να αλλάζεις ένα τη φορά.",
                PID + "/p-and-i-gains-log-flights"));
        s.add(info("2.3", "Φάση Γ · PID", "PD Balance – Εξαγωγή",
                "Κατέβασε το btfl_all.bbl με όλα τα sessions.",
                PID + "/extract-logfile"));
        s.add(analyze("2.4", "Φάση Γ · PID", "PD Balance – Ανάλυση",
                "Άνοιξε το Step Response. Θέλεις γρήγορο rise, ελάχιστο overshoot και settle κοντά στο 1, "
                        + "χωρίς συνεχή ταλάντωση (τα μικρά wobble είναι απλώς θόρυβος). Διάλεξε το Damping "
                        + "slider της καλύτερης πτήσης. Roll και Pitch μπορεί να θέλουν διαφορετική τιμή — "
                        + "κράτα το pitch με το Pitch Damping / Pitch Tracking slider.\n\n"
                        + stepTable() + "\n\n"
                        + "Κράτα το D λίγο συντηρητικό (ελαφρώς μεγαλύτερο P/D) ώστε να μπορείς μετά να "
                        + "ανεβάσεις τον Master multiplier (το D συνήθως είναι ο περιορισμός).",
                PID + "/p-and-i-gains-analysis", "pid"));
        s.add(info("2.5", "Φάση Γ · PID", "Master Multiplier – Πέτα & log",
                "Άφησε το Damping στο βέλτιστο και σάρωσε τον Master multiplier με βήμα 0.2: "
                        + "0.8 → 1.0 → 1.2 → 1.4 → 1.6 → 1.8. Ίδιο πρωτόκολλο (απότομα flicks, ~20s ανά τιμή).",
                PID + "/master-slider-log-flights"));
        s.add(analyze("2.6", "Φάση Γ · PID", "Master Multiplier – Ανάλυση",
                "Το σχήμα της step response δεν αλλάζει πολύ με τον Master (το καθορίζει ο λόγος P:D) — "
                        + "αυτό που αλλάζει είναι η καθυστέρηση (latency). Ανέβασέ τον μέχρι να συμβεί ένα "
                        + "από τα εξής: (α) το latency δεν πέφτει άλλο, (β) εμφανίζεται ταλάντωση/ripple, "
                        + "(γ) το dterm noise αυξάνεται απότομα. Διάλεξε τη μέγιστη τιμή χωρίς ταλάντωση και "
                        + "μετά κατέβα 1-2 σκαλοπάτια για ασφάλεια (bent props). Χρησιμοποίησε latency / "
                        + "cross-correlation που δίνει το εργαλείο.",
                PID + "/master-slider-analysis", "pid"));
        s.add(info("2.7", "Φάση Γ · PID", "I-Term / PI Balance",
                "Επανέφερε το I slider (Drift-Wobble) στο 1.0. Το step response ΔΕΝ δείχνει διαφορές στο I "
                        + "σε rate mode, οπότε ρύθμισέ το στην πτήση: σε hover / throttle chops η μύτη δεν "
                        + "πρέπει να «γυρίζει» ή να driftάρει (ανέβασε I), αλλά ούτε να κάνει bounce-back "
                        + "μετά από flicks (κατέβασε I).\n\n"
                        + "I (R/P): 60-80 / 80-120 / 120-150 (υψηλό = καλύτερο hover, κίνδυνος bounce-back)",
                OSCAR));
        s.add(info("2.8", "Φάση Γ · PID", "Feedforward / Stick Response",
                "Τελευταίο από τα βασικά: το Stick Response (FF). Ανεβάζοντάς το μειώνεις το lag και δίνεις "
                        + "snappy αίσθηση, αλλά FF > 1.0 χαλάει τον βέλτιστο λόγο P:D και μπορεί να δώσει "
                        + "overshoot στο τέλος των κινήσεων. Σύγκρινε setpoint με gyro και διάλεξε το "
                        + "μικρότερο lag χωρίς overshoot.\n\n"
                        + "FF (roll/pitch):  80-100 χαμηλό · 100-140 τυπικό · 140-200 υψηλό",
                PID + "/feedforward-slider-analysis"));
        s.add(analyze("2.9", "Φάση Γ · PID", "Feedforward – Ανάλυση",
                "Θέλεις τις γραμμές setpoint (πού θέλει ο pilot) και gyro (πού πήγε) όσο πιο κοντά γίνεται "
                        + "— μικρή καθυστέρηση = πιο snappy. Διάλεξε την πτήση με το μικρότερο lag.\n\n"
                        + "Tracking error RMS:  <2 deg/s καλό · 2-5 αποδεκτό · >5 πρόβλημα (ανέβασε P/I/FF)",
                PID + "/feedforward-slider-analysis", "tracking"));
        s.add(info("2.10", "Φάση Γ · PID", "Dynamic Damping (D Max) & D Gain",
                "Τελευταίο το Dynamic Damping (D Max): κρατά το base D λίγο πιο χαμηλό και το D-Max πιο "
                        + "ψηλό ώστε να έχεις damping στις γρήγορες κινήσεις (λιγότερο propwash) χωρίς να "
                        + "«καίγονται» οι κινητήρες στο hover. Μεγάλο D-Max/D-Min gap = πιο μαλακή πτήση.\n\n"
                        + dGainHint(p) + "\n\n" + dmaxTable(),
                BF_PID));
        return s;
    }

    private static List<Step> finishing(Profile p) {
        List<Step> s = new ArrayList<>();
        s.add(info("3.1", "Φάση Δ · Ολοκλήρωση", "TPA (Throttle PID Attenuation)",
                (p.longRange()
                        ? "Σε long range κρατάς το TPA σχετικά χαμηλό/αργό για σταθερότητα: tpa_rate ~10-15%, "
                                + "tpa_breakpoint ~1350-1500."
                        : "Το TPA μειώνει P/D όσο ανεβαίνει το γκάζι. Συνήθως tpa_rate ~20-35%, "
                                + "tpa_breakpoint ~1350. Σε high-KV/6S+ πήγαινε 65-75%.\n\n"
                                + "Σε BF 4.3+ λέγεται `tpa_rate` / `tpa_breakpoint`."),
                BF_PID));
        s.add(info("3.2", "Φάση Δ · Ολοκλήρωση", "Dynamic Idle",
                "Ρύθμισε το dynamic idle ώστε ο κινητήρας να κρατά αέρα στις απότομες εκρήξεις γκαζιού / "
                        + "ψηλά γκάζια με χαμηλό throttle (dump). Χρησιμοποίησε τον πίνακα του οδηγού ανά "
                        + "μέγεθος/pitch έλικα."
                        + (p.longRange() ? " Σε long range κράτα το όσο πιο χαμηλά γίνεται χωρίς desync, για οικονομία." : ""),
                PID + "/dynamic-idle"));
        s.add(info("3.3", "Φάση Δ · Ολοκλήρωση", "Anti-Gravity (προαιρετικά)",
                "Αν είδες το gyro να «σκάει» σε απότομες αλλαγές γκαζιού, ενεργοποίησε Anti-Gravity για να "
                        + "μειώσει το I στο sharp throttle. Αλλιώς άφησέ το OFF.",
                BF_PID));
        s.add(info("3.4", "Φάση Δ · Ολοκλήρωση", "Propwash / Motor health",
                "Αν έχεις propwash σε απότομες καταβάσεις: ανέβασε λίγο D / ρύθμισε D-Max-D-Min ratio, "
                        + "ενεργοποίησε Dynamic Idle, ή σκέψου Thrust Linearization.\n\n"
                        + "Propwash severity:  0-20 καλό · 20-50 μέτριο (D-Max/D-Min + φίλτρα) · 50+ σοβαρό\n"
                        + "Motor saturation: <5% καλό · 5-15% προσοχή · >15% (χαμήλωσε authority/PID)\n"
                        + "Motor differential: <15% καλό · 15-30% προσοχή · >30% (balance ή στραβή έλικα)",
                OSCAR));
        s.add(analyze("3.5", "Φάση Δ · Ολοκλήρωση", "Έλεγχος ανωμαλιών",
                "Τρέξε τον έλεγχο ανωμαλιών για να βρεις κόκκινα σημάδια σε όλη την πτήση: motor "
                        + "saturation, battery sag, gyro noise spikes, motor imbalance, desync και I-Term "
                        + "windup. Διόρθωσε πρώτα ό,τι έχει το μεγαλύτερο impact.",
                null, "anomaly"));
        s.add(info("3.6", "Φάση Δ · Ολοκλήρωση", "Τελικός έλεγχος με το χέρι",
                "Ξαναπέτα: hover, γρήγορα flicks, απότομα γκάζια, ριπές. Δεν πρέπει να ακούς/νιώθεις "
                        + "ταλάντωση, bounce-back ή θόρυβο κινητήρων. Αν κάτι εμφανιστεί, ξαναγύρνα στο "
                        + "αντίστοιχο βήμα (φάσμα/overshoot/settle).", OSCAR));
        return s;
    }

    private static String rpmFilterNote(Profile p) {
        if (!p.rpmCapable()) {
            return "ΠΡΟΣΟΧΗ: Stock BLHeli_S ΔΕΝ υποστηρίζει bidirectional DShot/RPM filter. Ανέβασε "
                    + "Bluejay (flash το ESC) για να το ενεργοποιήσεις.";
        }
        return "RPM capable ESG/firmware: κράτα το RPM filter ON. Πρότεινε harmonics 2-3 ανάλογα το build.";
    }

    private static String pidTable() {
        return "P (R/P)   30-40 / 42-55 / 60-80   (χαμηλό/τυπικό/υψηλό)\n"
                + "I (R/P)   60-80 / 80-120 / 120-150 (υψηλό = καλύτερο hover, κίνδυνος bounce-back)\n"
                + "D-Max     25-30 / 32-42 / 45-60   (υψηλό = propwash, κίνδυνος hot motors)\n"
                + "D-Min     18-22 / 22-30 / 30-40   (ενεργό D σε χαμηλό stick)\n"
                + "FF        80-100 / 100-140 / 140-200\n"
                + "Yaw P     20-30 / 30-45 / 45-60 · Yaw I 60-80 / 80-120 / 120-150";
    }

    private static String stepTable() {
        return "Overshoot   <5% καλό · 5-15% αποδεκτό · >15% πρόβλημα (χαμήλωσε P / ανέβασε D)\n"
                + "Undershoot  <3% καλό · 3-8% · >8% (ανέβασε P/FF)\n"
                + "Rise time   15-25ms καλό · 25-40 · >40ms (ανέβασε P/FF)\n"
                + "Settling    <50ms καλό · 50-80 · >80ms (ρύθμισε D / D-Max)";
    }

    private static String dmaxTable() {
        return "D-Max (R/P): 25-30 / 32-42 / 45-60 (χαμηλό/τυπικό/υψηλό)\n"
                + "D-Min (R/P): 18-22 / 22-30 / 30-40\n"
                + "Υψηλό D-Max = καλύτερο propwash, ρίσκο hot motors.";
    }

    private static String dGainHint(Profile p) {
        int base;
        if (p.whoop()) {
            base = 65;
        } else if (p.cells() >= 6) {
            base = 25;
        } else {
            base = 35;
        }
        if (p.longRange()) base = Math.max(20, base - 8);
        if (p.racing()) base = base + 8;
        String steer = p.longRange()
                ? "Κράτα το λίγο πιο χαμηλά (ομαλότητα/οικονομία) και ανέβασέ το μόνο αν δεις overshoot."
                : "Ξεκίνα εκεί και ανέβασε μέχρι να εξαφανιστεί το overshoot χωρίς να ακούγεται θόρυβος D.";
        return "Ξεκίνα γύρω στο D ≈ " + base + " (σε μονάδες Betaflight). " + steer;
    }

    private static Step info(String id, String phase, String title, String instructions, String url) {
        return new Step(id, phase, title, TYPE_INFO, instructions, url, null);
    }

    private static Step analyze(String id, String phase, String title, String instructions, String url,
                                String analyzer) {
        return new Step(id, phase, title, TYPE_ANALYZE, instructions, url, analyzer);
    }
}
