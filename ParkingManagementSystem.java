import javafx.animation.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;

public class ParkingManagementSystem extends Application {

    /* ============================== THEME ============================== */
    static final String RED       = "#C8102E";
    static final String RED_DARK  = "#8C0C21";
    static final String RED_DEEP  = "#5E0716";
    static final String WHITE     = "#FFFFFF";
    static final String PAPER     = "#FBF7F4";
    static final String PANEL     = "#F4EEEA";
    static final String INK       = "#231F1C";
    static final String INK_SOFT  = "#6B615B";
    static final String LINE      = "#E3D9D2";
    static final String OK        = "#2E7D46";
    static final String OK_BG     = "#E7F4EA";
    static final String WARN      = "#B8860B";
    static final String WARN_BG   = "#FBF0DD";

    static final String FONT_UI   = "Arial";
    static final String FONT_MONO = "Courier New";

    static final int CAPACITY = 100;
    static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /* ============================== MODEL ============================== */

    enum VehicleType { CAR, BIKE, HEAVY;
        String label() { return this == CAR ? "CAR" : this == BIKE ? "BIKE" : "HEAVY VEHICLE"; }
    }

    enum Timeframe { MONTHLY, WEEKLY }
    enum Status { PAID, UNPAID }

    static class Floor {
        final String id, level, title, rate;
        final VehicleType vehicleType; // null for member floor (mixed)
        Floor(String id, String level, String title, VehicleType vt, String rate) {
            this.id = id; this.level = level; this.title = title; this.vehicleType = vt; this.rate = rate;
        }
    }

    static final List<Floor> FLOORS = List.of(
            new Floor("A", "GROUND FLOOR", "Heavy Vehicles", VehicleType.HEAVY, "Rs 50 every hour \u00b7 no free hour"),
            new Floor("B", "1ST FLOOR",    "Members Reserved", null,            "Monthly / Weekly membership"),
            new Floor("C", "2ND FLOOR",    "Bikes",           VehicleType.BIKE, "1st hour free \u00b7 then Rs 20/hr"),
            new Floor("D", "3RD FLOOR",    "Cars",            VehicleType.CAR,  "1st hour free \u00b7 then Rs 30/hr")
    );

    static Floor floorById(String id) {
        for (Floor f : FLOORS) if (f.id.equals(id)) return f;
        throw new IllegalArgumentException(id);
    }

    static class ParkedVehicle {
        final String id;
        final String regNo;
        final VehicleType vehicleType;
        final LocalDateTime entryTime;
        final boolean isMember;
        ParkedVehicle(String id, String regNo, VehicleType vt, LocalDateTime entry, boolean isMember) {
            this.id = id; this.regNo = regNo; this.vehicleType = vt; this.entryTime = entry; this.isMember = isMember;
        }
    }

    static class Member {
        final String id;
        final VehicleType vehicleType;
        final String regNo;
        final int slot;
        final Timeframe timeframe;
        final String period;
        final ObjectProperty<Status> status;
        Member(String id, VehicleType vt, String regNo, int slot, Timeframe tf, String period, Status status) {
            this.id = id; this.vehicleType = vt; this.regNo = regNo; this.slot = slot;
            this.timeframe = tf; this.period = period; this.status = new SimpleObjectProperty<>(status);
        }
    }

    /** Fee result: hours billed + amount due. */
    record Fee(long hours, int amount) {}

    static Fee calcFee(VehicleType vt, LocalDateTime entry, LocalDateTime now) {
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(entry, now));
        long hours = Math.max(1, (long) Math.ceil(minutes / 60.0));
        int amount;
        switch (vt) {
            case HEAVY -> amount = (int) (hours * 50);
            case CAR   -> amount = hours <= 1 ? 0 : (int) ((hours - 1) * 30);
            case BIKE  -> amount = hours <= 1 ? 0 : (int) ((hours - 1) * 20);
            default -> amount = 0;
        }
        return new Fee(hours, amount);
    }

    static String randomId() {
        String s = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return s.substring(0, 7);
    }

    /* ============================== STATE ============================== */

    // floorId -> (slot -> ParkedVehicle)
    private final Map<String, Map<Integer, ParkedVehicle>> occupied = new LinkedHashMap<>();
    private final ObservableList<Member> members = FXCollections.observableArrayList();
    private final Random rng = new Random();

    // UI live-refresh hooks
    private final List<Runnable> refreshListeners = new ArrayList<>();

    private Stage primaryStage;
    private BorderPane rootLayout;
    private StackPane contentArea;
    private HBox navBar;
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private StackPane toastLayer;
    private VBox dashboardFloorGrid;

    /* ============================== ENTRY POINT ============================== */

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        for (Floor f : FLOORS) occupied.put(f.id, new LinkedHashMap<>());

        rootLayout = new BorderPane();
        rootLayout.setStyle("-fx-background-color: " + PAPER + ";");

        rootLayout.setTop(buildNavBar());

        contentArea = new StackPane();
        contentArea.setPadding(new Insets(24));
        contentArea.setAlignment(Pos.TOP_CENTER);
        ScrollPane scroller = new ScrollPane(contentArea);
        scroller.setFitToWidth(true);
        scroller.setStyle("-fx-background: " + PAPER + "; -fx-background-color: " + PAPER + ";");
        rootLayout.setCenter(scroller);

        StackPane appStack = new StackPane(rootLayout);
        toastLayer = new StackPane();
        toastLayer.setPickOnBounds(false);
        toastLayer.setAlignment(Pos.BOTTOM_CENTER);
        toastLayer.setPadding(new Insets(0, 0, 24, 0));
        appStack.getChildren().add(toastLayer);

        showDashboard();

        Scene scene = new Scene(appStack, 1080, 720);
        stage.setScene(scene);
        stage.setTitle("Central Parking System");
        stage.show();
    }

    /* ============================== NAV BAR ============================== */

    private HBox buildNavBar() {
        navBar = new HBox(20);
        navBar.setPadding(new Insets(14, 22, 14, 22));
        navBar.setAlignment(Pos.CENTER_LEFT);
        navBar.setStyle("-fx-background-color: " + INK + "; -fx-border-color: transparent transparent " + RED + " transparent; -fx-border-width: 0 0 4 0;");

        Label mark = new Label("P");
        mark.setStyle("-fx-background-color: " + RED + "; -fx-text-fill: white; -fx-font-weight: 900; -fx-font-size: 18px; " +
                "-fx-border-color: white; -fx-border-width: 2; -fx-background-radius: 4; -fx-border-radius: 4;" +
                "-fx-min-width: 38; -fx-min-height: 38; -fx-alignment: center;");
        mark.setPrefSize(38, 38);
        mark.setAlignment(Pos.CENTER);

        Label title = new Label("CENTRAL PARKING SYSTEM");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font(FONT_UI, FontWeight.EXTRA_BOLD, 15));
        Label sub = new Label("4-Floor Automated Facility");
        sub.setTextFill(Color.web("#C9C2BC"));
        sub.setFont(Font.font(FONT_UI, 11));
        VBox titleBox = new VBox(2, title, sub);

        HBox brand = new HBox(12, mark, titleBox);
        brand.setAlignment(Pos.CENTER_LEFT);

        HBox tabs = new HBox(6);
        tabs.setAlignment(Pos.CENTER_LEFT);
        navButtons.put("dashboard", navBtn("Dashboard", () -> showDashboard()));
        navButtons.put("entry", navBtn("New Entry", () -> showNewEntry()));
        navButtons.put("members", navBtn("Members", () -> showMembers()));
        navButtons.put("checkout", navBtn("Checkout", () -> showCheckout()));
        tabs.getChildren().addAll(navButtons.values());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button reset = new Button("\u21bb Reset");
        reset.setStyle(navBaseStyle(false) + "-fx-text-fill: #C9C2BC;");
        reset.setOnAction(e -> resetAll());

        navBar.getChildren().addAll(brand, spacer, tabs, reset);
        return navBar;
    }

    private String navBaseStyle(boolean active) {
        return "-fx-background-color: " + (active ? RED : "transparent") + ";" +
               "-fx-border-color: " + (active ? RED : "#4a4440") + ";" +
               "-fx-border-width: 1; -fx-background-radius: 4; -fx-border-radius: 4;" +
               "-fx-padding: 8 14 8 14; -fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand;";
    }

    private Button navBtn(String label, Runnable action) {
        Button b = new Button(label);
        b.setTextFill(Color.web("#E7E2DD"));
        b.setStyle(navBaseStyle(false));
        b.setOnAction(e -> {
            setActiveTab(label);
            action.run();
        });
        return b;
    }

    private void setActiveTab(String label) {
        for (Button b : navButtons.values()) {
            boolean active = b.getText().equals(label);
            b.setStyle(navBaseStyle(active));
            b.setTextFill(active ? Color.WHITE : Color.web("#E7E2DD"));
        }
    }

    /* ============================== TOAST ============================== */

    private void notify(String msg, boolean ok) {
        Label icon = new Label(ok ? "\u2714" : "\u26a0");
        icon.setTextFill(Color.WHITE);
        Label text = new Label(msg);
        text.setTextFill(Color.WHITE);
        text.setFont(Font.font(FONT_UI, 13));
        HBox box = new HBox(8, icon, text);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(12, 18, 12, 18));
        box.setStyle("-fx-background-color: " + (ok ? INK : RED_DEEP) + "; -fx-background-radius: 6;");
        DropShadow ds = new DropShadow(20, Color.rgb(0, 0, 0, 0.35));
        box.setEffect(ds);

        toastLayer.getChildren().add(box);
        FadeTransition in = new FadeTransition(Duration.millis(180), box);
        in.setFromValue(0); in.setToValue(1); in.play();

        PauseTransition wait = new PauseTransition(Duration.millis(2600));
        wait.setOnFinished(e -> {
            FadeTransition out = new FadeTransition(Duration.millis(300), box);
            out.setFromValue(1); out.setToValue(0);
            out.setOnFinished(ev -> toastLayer.getChildren().remove(box));
            out.play();
        });
        wait.play();
    }

    private void resetAll() {
        for (Floor f : FLOORS) occupied.get(f.id).clear();
        members.clear();
        refresh();
        notify("Demo data cleared", true);
    }

    private void refresh() {
        for (Runnable r : refreshListeners) r.run();
    }

    /* ============================== HELPERS ============================== */

    private Set<String> allActiveRegNos() {
        Set<String> set = new HashSet<>();
        for (Map<Integer, ParkedVehicle> floorMap : occupied.values())
            for (ParkedVehicle v : floorMap.values()) set.add(v.regNo);
        return set;
    }

    private List<Integer> freeSlots(String floorId) {
        Set<Integer> taken = occupied.get(floorId).keySet();
        List<Integer> free = new ArrayList<>();
        for (int i = 1; i <= CAPACITY; i++) if (!taken.contains(i)) free.add(i);
        return free;
    }

    private Integer pickRandomSlot(String floorId) {
        List<Integer> free = freeSlots(floorId);
        if (free.isEmpty()) return null;
        return free.get(rng.nextInt(free.size()));
    }

    /* ============================== SECTION HEADER ============================== */

    private VBox sectionHead(String title, String sub) {
        Label t = new Label(title);
        t.setFont(Font.font(FONT_UI, FontWeight.BOLD, 20));
        t.setTextFill(Color.web(INK));
        Label s = new Label(sub);
        s.setFont(Font.font(FONT_UI, 13));
        s.setTextFill(Color.web(INK_SOFT));
        VBox box = new VBox(4, t, s);
        return box;
    }

    private Node container(Node... children) {
        VBox box = new VBox(16);
        box.setMaxWidth(1040);
        box.setFillWidth(true);
        box.getChildren().addAll(children);
        return box;
    }

    /* ============================================================
       DASHBOARD
       ============================================================ */
    private void showDashboard() {
        setActiveTab("Dashboard");
        refreshListeners.clear();

        FlowPane grid = new FlowPane(16, 16);
        grid.setPrefWrapLength(1040);

        FlowPane stats = new FlowPane(14, 14);

        VBox head = (VBox) sectionHead("Floor Occupancy", "");
        Label headSub = (Label) head.getChildren().get(1);

        Runnable rebuild = () -> {
            grid.getChildren().clear();
            int totalOcc = 0;
            for (Floor f : FLOORS) {
                int count = occupied.get(f.id).size();
                totalOcc += count;
                grid.getChildren().add(floorCard(f, count));
            }
            headSub.setText(totalOcc + " / " + (CAPACITY * FLOORS.size()) + " vehicles across the facility");

            stats.getChildren().clear();
            long activeMembers = members.stream().filter(m -> m.status.get() == Status.PAID).count();
            long unpaidMembers = members.stream().filter(m -> m.status.get() == Status.UNPAID).count();
            int nonMemberParked = occupied.get("A").size() + occupied.get("C").size() + occupied.get("D").size();
            int freeSlots = (CAPACITY * FLOORS.size()) - totalOcc;
            stats.getChildren().addAll(
                    statBox(String.valueOf(activeMembers), "Active Members"),
                    statBox(String.valueOf(unpaidMembers), "Unpaid Members"),
                    statBox(String.valueOf(nonMemberParked), "Non-member Vehicles Parked"),
                    statBox(String.valueOf(freeSlots), "Free Slots Facility-wide")
            );
        };
        rebuild.run();
        refreshListeners.add(rebuild);

        Label statsTitle = new Label("Live Summary");
        statsTitle.setFont(Font.font(FONT_UI, FontWeight.BOLD, 20));
        statsTitle.setTextFill(Color.web(INK));
        VBox statsHead = new VBox(statsTitle);
        statsHead.setPadding(new Insets(24, 0, 0, 0));

        Node page = container(head, grid, statsHead, stats);
        setContent(page);
    }

    private VBox floorCard(Floor f, int count) {
        VBox card = new VBox(8);
        card.setPrefWidth(230);
        card.setPadding(new Insets(16));
        card.setStyle("-fx-background-color: white; -fx-border-color: " + LINE + "; " +
                "-fx-background-radius: 0 0 6 6; -fx-border-radius: 0 0 6 6;" +
                "-fx-border-width: 0 1 1 1;");

        Region topBar = new Region();
        topBar.setPrefHeight(5);
        topBar.setStyle("-fx-background-color: " + RED + "; -fx-background-radius: 6 6 0 0;");
        VBox outer = new VBox(topBar, card);
        outer.setPrefWidth(230);

        HBox top = new HBox();
        Label letter = new Label(f.id);
        letter.setFont(Font.font(FONT_UI, FontWeight.EXTRA_BOLD, 30));
        letter.setTextFill(Color.web(RED));
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label icon = new Label(vehicleGlyph(f));
        icon.setFont(Font.font(18));
        icon.setTextFill(Color.web(RED));
        top.getChildren().addAll(letter, sp, icon);

        Label level = new Label(f.level);
        level.setFont(Font.font(FONT_UI, FontWeight.BOLD, 11));
        level.setTextFill(Color.web(INK_SOFT));
        Label titleLbl = new Label(f.title);
        titleLbl.setFont(Font.font(FONT_UI, FontWeight.BOLD, 16));
        titleLbl.setTextFill(Color.web(INK));

        double pct = (count / (double) CAPACITY) * 100.0;
        StackPane barBg = new StackPane();
        barBg.setPrefHeight(8);
        barBg.setStyle("-fx-background-color: " + PANEL + "; -fx-background-radius: 4;");
        Region barFill = new Region();
        barFill.setStyle("-fx-background-color: linear-gradient(from 0% 0% to 100% 0%, " + RED + ", " + RED_DARK + "); -fx-background-radius: 4;");
        barFill.setPrefHeight(8);
        barBg.getChildren().add(barFill);
        StackPane.setAlignment(barFill, Pos.CENTER_LEFT);
        barBg.widthProperty().addListener((obs, o, n) -> barFill.setPrefWidth(n.doubleValue() * (pct / 100.0)));

        Label occLbl = new Label(count + " / " + CAPACITY + " occupied (" + Math.round(pct) + "%)");
        occLbl.setFont(Font.font(FONT_UI, 12));
        occLbl.setTextFill(Color.web(INK_SOFT));

        Separator sep = new Separator();
        Label rateLbl = new Label(f.rate);
        rateLbl.setFont(Font.font(FONT_UI, 12));
        rateLbl.setTextFill(Color.web(INK));
        rateLbl.setWrapText(true);

        card.getChildren().setAll(top, level, titleLbl, barBg, occLbl, sep, rateLbl);
        return outer;
    }

    private String vehicleGlyph(Floor f) {
        if (f.vehicleType == VehicleType.HEAVY) return "\ud83d\ude9b";
        if (f.vehicleType == VehicleType.BIKE) return "\ud83d\udeb2";
        if (f.vehicleType == VehicleType.CAR) return "\ud83d\ude97";
        return "\ud83d\udee1";
    }

    private VBox statBox(String num, String label) {
        Label n = new Label(num);
        n.setFont(Font.font(FONT_UI, FontWeight.EXTRA_BOLD, 26));
        n.setTextFill(Color.web(RED));
        Label l = new Label(label);
        l.setFont(Font.font(FONT_UI, 12));
        l.setTextFill(Color.web(INK_SOFT));
        l.setWrapText(true);
        l.setAlignment(Pos.CENTER);
        l.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox box = new VBox(4, n, l);
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(160);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color: white; -fx-border-color: " + LINE + "; -fx-border-radius: 6; -fx-background-radius: 6;");
        return box;
    }

    private void setContent(Node n) {
        contentArea.getChildren().setAll(n);
    }

    /* ============================================================
       NEW ENTRY
       ============================================================ */
    private final ObjectProperty<VehicleType> entryVehicleType = new SimpleObjectProperty<>(VehicleType.CAR);

    private void showNewEntry() {
        setActiveTab("New Entry");
        refreshListeners.clear();

        Node head = sectionHead("New Vehicle Entry", "Camera scans the plate, the machine assigns a slot and prints a slip.");

        HBox vtRow = new HBox(10);
        Map<VehicleType, Button> vtButtons = new LinkedHashMap<>();
        for (VehicleType vt : VehicleType.values()) {
            Button b = vehicleTypeButton(vt);
            b.setOnAction(e -> {
                entryVehicleType.set(vt);
                for (Map.Entry<VehicleType, Button> en : vtButtons.entrySet())
                    styleVtButton(en.getValue(), en.getKey() == vt);
            });
            vtButtons.put(vt, b);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        vtRow.getChildren().addAll(vtButtons.values());
        styleVtButton(vtButtons.get(VehicleType.CAR), true);

        Label regLabel = fieldLabel("Registration No.");
        TextField regField = new TextField();
        regField.setPromptText("e.g. ABC-12-098");
        regField.setStyle(inputStyle() + " -fx-font-family: '" + FONT_MONO + "';");
        regField.textProperty().addListener((o, ov, nv) -> {
            String up = nv.toUpperCase();
            if (!up.equals(nv)) regField.setText(up);
        });

        Label timeLabel = fieldLabel("Entry Time (optional \u2014 defaults to now)");
        DatePicker datePicker = new DatePicker(java.time.LocalDate.now());
        datePicker.setStyle(inputStyle());
        Spinner<Integer> hourSpin = new Spinner<>(0, 23, LocalDateTime.now().getHour());
        Spinner<Integer> minSpin = new Spinner<>(0, 59, LocalDateTime.now().getMinute());
        hourSpin.setEditable(true); minSpin.setEditable(true);
        hourSpin.setPrefWidth(70); minSpin.setPrefWidth(70);
        HBox timeRow = new HBox(8, datePicker, new Label("H:"), hourSpin, new Label("M:"), minSpin);
        timeRow.setAlignment(Pos.CENTER_LEFT);

        Label assignNote = new Label();
        assignNote.setWrapText(true);
        assignNote.setFont(Font.font(FONT_UI, 13));
        assignNote.setStyle("-fx-background-color: " + PANEL + "; -fx-padding: 10 12 10 12; -fx-background-radius: 4;");

        Runnable updateNote = () -> {
            VehicleType vt = entryVehicleType.get();
            String floorId = vt == VehicleType.HEAVY ? "A" : vt == VehicleType.BIKE ? "C" : "D";
            int free = freeSlots(floorId).size();
            assignNote.setText("Will be assigned to Floor " + floorId + " \u00b7 " + free + " of " + CAPACITY + " slots free");
        };
        updateNote.run();
        entryVehicleType.addListener((o, ov, nv) -> updateNote.run());
        refreshListeners.add(updateNote);

        Button scanBtn = new Button("\u25a4  Scan & Issue Slip");
        scanBtn.setMaxWidth(Double.MAX_VALUE);
        scanBtn.setStyle(primaryBtnStyle());
        scanBtn.setOnAction(e -> {
            LocalDateTime entry = LocalDateTime.of(datePicker.getValue(), java.time.LocalTime.of(hourSpin.getValue(), minSpin.getValue()));
            performScanThenEntry(entryVehicleType.get(), regField.getText(), entry, scanBtn, updateNote, regField);
        });

        VBox formBox = new VBox(14, vtRow, fieldBox(regLabel, regField), fieldBox(timeLabel, timeRow), assignNote, scanBtn);
        formBox.setPadding(new Insets(22));
        formBox.setMaxWidth(520);
        formBox.setStyle("-fx-background-color: white; -fx-border-color: " + LINE + "; -fx-border-radius: 8; -fx-background-radius: 8;");

        Node page = container(head, formBox);
        setContent(page);
    }

    private void performScanThenEntry(VehicleType vt, String regNoRaw, LocalDateTime entryTime,
                                       Button scanBtn, Runnable updateNote, TextField regField) {
        String reg = regNoRaw == null ? "" : regNoRaw.trim().toUpperCase();
        if (reg.isEmpty()) { notify("Enter a registration number", false); return; }
        if (allActiveRegNos().contains(reg)) {
            notify(reg + " is already parked inside. Double entry blocked.", false);
            return;
        }
        // member recognition
        for (Member m : members) {
            if (m.regNo.equals(reg) && m.vehicleType == vt && m.status.get() == Status.PAID) {
                notify("Welcome back, member! " + reg + " already has slot B-" + m.slot + ". No slip needed.", true);
                return;
            }
        }

        scanBtn.setDisable(true);
        scanBtn.setText("Scanning...");
        Stage scanStage = buildScanOverlay();
        scanStage.show();

        PauseTransition pause = new PauseTransition(Duration.millis(1250));
        pause.setOnFinished(ev -> {
            scanStage.close();
            scanBtn.setDisable(false);
            scanBtn.setText("\u25a4  Scan & Issue Slip");

            String floorId = vt == VehicleType.HEAVY ? "A" : vt == VehicleType.BIKE ? "C" : "D";
            Integer slot = pickRandomSlot(floorId);
            if (slot == null) {
                notify("Floor " + floorId + " is full (100/100). No slots available.", false);
                return;
            }
            String id = randomId();
            ParkedVehicle pv = new ParkedVehicle(id, reg, vt, entryTime, false);
            occupied.get(floorId).put(slot, pv);
            refresh();
            updateNote.run();
            regField.clear();
            showTicketSlip(pv, floorId, slot);
        });
        pause.play();
    }

    private Stage buildScanOverlay() {
        Stage s = new Stage(StageStyle.TRANSPARENT);
        s.initOwner(primaryStage);
        s.initModality(Modality.WINDOW_MODAL);

        StackPane box = new StackPane();
        box.setPrefSize(220, 160);
        box.setStyle("-fx-background-color: rgba(20,18,17,0.95); -fx-border-color: " + RED + "; -fx-border-width: 2; -fx-background-radius: 6; -fx-border-radius: 6;");

        Rectangle scanLine = new Rectangle(200, 3);
        scanLine.setFill(Color.web(RED));
        scanLine.setEffect(new DropShadow(12, Color.web(RED)));

        Label camIcon = new Label("\ud83d\udcf7");
        camIcon.setFont(Font.font(30));
        camIcon.setTextFill(Color.web("#555"));

        Label txt = new Label("Reading number plate\u2026");
        txt.setTextFill(Color.WHITE);
        txt.setFont(Font.font(FONT_UI, 12));

        VBox inner = new VBox(10, camIcon, txt);
        inner.setAlignment(Pos.CENTER);

        box.getChildren().addAll(inner, scanLine);
        StackPane.setAlignment(scanLine, Pos.TOP_CENTER);

        TranslateTransition tt = new TranslateTransition(Duration.millis(900), scanLine);
        tt.setFromY(-70);
        tt.setToY(70);
        tt.setCycleCount(Animation.INDEFINITE);
        tt.setAutoReverse(true);
        tt.play();

        Scene sc = new Scene(box);
        sc.setFill(Color.TRANSPARENT);
        s.setScene(sc);
        s.centerOnScreen();
        return s;
    }

    private Button vehicleTypeButton(VehicleType vt) {
        String glyph = vt == VehicleType.CAR ? "\ud83d\ude97" : vt == VehicleType.BIKE ? "\ud83d\udeb2" : "\ud83d\ude9b";
        Button b = new Button(glyph + "\n" + vt.label());
        b.setWrapText(true);
        b.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        b.setPrefHeight(70);
        b.setMaxWidth(Double.MAX_VALUE);
        styleVtButton(b, false);
        return b;
    }

    private void styleVtButton(Button b, boolean active) {
        b.setStyle("-fx-border-width: 2; -fx-border-color: " + (active ? RED : LINE) + ";" +
                "-fx-background-color: " + (active ? "#FCEBEE" : PAPER) + ";" +
                "-fx-text-fill: " + (active ? RED : INK) + ";" +
                "-fx-font-weight: bold; -fx-font-size: 11px; -fx-background-radius: 6; -fx-border-radius: 6; -fx-cursor: hand;");
    }

    private Label fieldLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font(FONT_UI, FontWeight.BOLD, 12));
        l.setTextFill(Color.web(INK_SOFT));
        return l;
    }

    private VBox fieldBox(Label label, Node field) {
        VBox v = new VBox(6, label, field);
        return v;
    }

    private String inputStyle() {
        return "-fx-border-color: " + LINE + "; -fx-border-radius: 4; -fx-background-radius: 4;" +
               "-fx-padding: 8 10 8 10; -fx-font-size: 13px; -fx-background-color: " + PAPER + ";";
    }

    private String primaryBtnStyle() {
        return "-fx-background-color: " + RED + "; -fx-text-fill: white; -fx-font-weight: bold; " +
               "-fx-font-size: 14px; -fx-padding: 12 18 12 18; -fx-background-radius: 5; -fx-cursor: hand;";
    }

    private String outlineBtnStyle() {
        return "-fx-background-color: white; -fx-border-color: " + LINE + "; -fx-text-fill: " + INK + ";" +
               "-fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 8 14 8 14; -fx-background-radius: 5; -fx-border-radius: 5; -fx-cursor: hand;";
    }

    /* ============================================================
       TICKET / CARD — vertical "stitched border" ticket look
       ============================================================ */

    /** Row of small circles imitating perforation holes along a ticket edge. */
    private HBox perforationRow() {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(0, 2, 8, 2));
        for (int i = 0; i < 12; i++) {
            Circle c = new Circle(3.5);
            c.setFill(Color.web(PAPER));
            row.getChildren().add(c);
        }
        return row;
    }

    /** Deterministic pseudo-barcode built from a seed string. */
    private HBox barcode(String seed) {
        HBox bc = new HBox(2);
        bc.setAlignment(Pos.BOTTOM_LEFT);
        bc.setPrefHeight(32);
        long s = 0;
        for (int i = 0; i < seed.length(); i++) s += (long) seed.charAt(i) * (i + 1);
        for (int i = 0; i < 34; i++) {
            s = (s * 9301 + 49297) % 233280;
            int w = 1 + (int) ((s / 233280.0) * 3);
            Rectangle r = new Rectangle(w, 32);
            r.setFill(i % 2 == 0 ? Color.web(INK) : Color.TRANSPARENT);
            bc.getChildren().add(r);
        }
        return bc;
    }

    /** Wraps ticket content in the red stitched (dashed inner line) border used by both slip & card. */
    private StackPane stitchedFrame(Node content) {
        StackPane outer = new StackPane();
        outer.setPadding(new Insets(6));
        outer.setStyle("-fx-background-color: white; -fx-background-radius: 6;");
        outer.setEffect(new DropShadow(24, Color.rgb(0, 0, 0, 0.35)));

        StackPane solidBorder = new StackPane(content);
        solidBorder.setStyle("-fx-border-color: " + RED + "; -fx-border-width: 3;");

        // dashed inset border drawn on top, purely decorative, mimicking stitching
        Rectangle dashed = new Rectangle();
        dashed.setFill(Color.TRANSPARENT);
        dashed.setStroke(Color.web(RED_DARK));
        dashed.setStrokeWidth(2);
        dashed.getStrokeDashArray().addAll(6.0, 5.0);
        dashed.setArcWidth(4);
        dashed.setArcHeight(4);
        dashed.setMouseTransparent(true);
        solidBorder.widthProperty().addListener((o, ov, nv) -> dashed.setWidth(nv.doubleValue() - 10));
        solidBorder.heightProperty().addListener((o, ov, nv) -> dashed.setHeight(nv.doubleValue() - 10));

        StackPane withDash = new StackPane(solidBorder, dashed);
        StackPane.setAlignment(dashed, Pos.CENTER);
        outer.getChildren().add(withDash);
        return outer;
    }

    private VBox ticketFieldRow(String label, String value, boolean mono, boolean big) {
        Label l = new Label(label);
        l.setFont(Font.font(FONT_UI, FontWeight.BOLD, 10));
        l.setTextFill(Color.web(INK_SOFT));
        Label v = new Label(value);
        v.setFont(Font.font(mono ? FONT_MONO : FONT_UI, FontWeight.BOLD, big ? 18 : 14));
        v.setTextFill(Color.web(big ? RED : INK));
        v.setWrapText(true);
        VBox box = new VBox(2, l, v);
        return box;
    }

    private void showTicketSlip(ParkedVehicle pv, String floorId, int slot) {
        Floor floor = floorById(floorId);
        VBox header = new VBox();
        header.setStyle("-fx-background-color: " + RED + ";");
        header.setPadding(new Insets(14, 16, 10, 16));

        HBox headerTop = new HBox();
        Label brand = new Label("CENTRAL PARKING");
        brand.setFont(Font.font(FONT_UI, FontWeight.EXTRA_BOLD, 11));
        brand.setTextFill(Color.WHITE);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label icon = new Label(vehicleGlyph(floor));
        icon.setFont(Font.font(18));
        headerTop.getChildren().addAll(brand, sp, icon);

        Label title = new Label("PARKING SLIP");
        title.setFont(Font.font(FONT_UI, FontWeight.EXTRA_BOLD, 20));
        title.setTextFill(Color.WHITE);
        title.setPadding(new Insets(6, 0, 6, 0));

        Label badge = new Label("FLOOR " + floorId);
        badge.setFont(Font.font(FONT_UI, FontWeight.EXTRA_BOLD, 11));
        badge.setTextFill(Color.web(RED));
        badge.setStyle("-fx-background-color: white; -fx-background-radius: 3;");
        badge.setPadding(new Insets(3, 8, 3, 8));

        header.getChildren().addAll(perforationRow(), headerTop, title, badge);

        VBox body = new VBox(12);
        body.setPadding(new Insets(16, 18, 18, 18));
        Fee dummy = calcFee(pv.vehicleType, pv.entryTime, pv.entryTime);
        body.getChildren().addAll(
                ticketFieldRow("VEHICLE TYPE", pv.vehicleType.label(), false, false),
                ticketFieldRow("REG NO.", pv.regNo, true, true),
                ticketFieldRow("GIVEN SLOT", floorId + "-" + slot, true, true),
                ticketFieldRow("TIME OF PARKING", pv.entryTime.format(DTF), true, false),
                new Separator(),
                rateNoteLabel(floor.rate),
                idNoteLabel("SLIP ID \u00b7 " + pv.id),
                barcode(pv.id + pv.regNo)
        );

        VBox ticketContent = new VBox(header, body);
        ticketContent.setPrefWidth(268);
        VBox withBottomPerf = new VBox(ticketContent, perforationRow());

        StackPane framed = stitchedFrame(withBottomPerf);
        showPopup(framed, "Parking Slip");
    }

    private void showMemberCardPopup(Member m) {
        VBox header = new VBox(8);
        header.setStyle("-fx-background-color: " + RED + ";");
        header.setPadding(new Insets(14, 16, 14, 16));
        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);
        Label shield = new Label("\ud83d\udee1");
        shield.setFont(Font.font(20));
        Label brand = new Label("CENTRAL PARKING");
        brand.setFont(Font.font(FONT_UI, FontWeight.EXTRA_BOLD, 11));
        brand.setTextFill(Color.WHITE);
        Label sub = new Label("MEMBER CARD \u00b7 FLOOR B");
        sub.setFont(Font.font(FONT_UI, FontWeight.BOLD, 13));
        sub.setTextFill(Color.WHITE);
        VBox brandBox = new VBox(2, brand, sub);
        top.getChildren().addAll(shield, brandBox);
        header.getChildren().add(top);

        StackPane photo = new StackPane();
        photo.setPrefHeight(70);
        photo.setStyle("-fx-background-color: " + PANEL + ";");
        Label vIcon = new Label(m.vehicleType == VehicleType.CAR ? "\ud83d\ude97" : "\ud83d\udeb2");
        vIcon.setFont(Font.font(38));
        photo.getChildren().add(vIcon);

        VBox body = new VBox(12);
        body.setPadding(new Insets(16, 18, 18, 18));

        HBox statusRow = new HBox(8);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        Label statusLbl = new Label("STATUS");
        statusLbl.setFont(Font.font(FONT_UI, FontWeight.BOLD, 10));
        statusLbl.setTextFill(Color.web(INK_SOFT));
        Region sp2 = new Region();
        HBox.setHgrow(sp2, Priority.ALWAYS);
        Label pill = statusPill(m.status.get());
        statusRow.getChildren().addAll(statusLbl, sp2, pill);

        body.getChildren().addAll(
                ticketFieldRow("VEHICLE TYPE", m.vehicleType.label(), false, false),
                ticketFieldRow("REG NO.", m.regNo, true, true),
                ticketFieldRow("GIVEN SLOT", "B-" + m.slot, true, true),
                ticketFieldRow("TIME FRAME", m.timeframe.name() + " \u00b7 " + m.period, true, false),
                statusRow,
                new Separator(),
                idNoteLabel("MEMBER ID \u00b7 " + m.id),
                barcode(m.id + m.regNo)
        );

        VBox cardContent = new VBox(header, photo, body);
        cardContent.setPrefWidth(268);

        StackPane framed = stitchedFrame(cardContent);
        showPopup(framed, "Member Card");
    }

    private Label statusPill(Status status) {
        boolean paid = status == Status.PAID;
        Label pill = new Label((paid ? "\u25cf " : "\u25cf ") + status.name());
        pill.setFont(Font.font(FONT_UI, FontWeight.EXTRA_BOLD, 11));
        pill.setTextFill(Color.web(paid ? OK : WARN));
        pill.setStyle("-fx-background-color: " + (paid ? OK_BG : WARN_BG) + "; -fx-background-radius: 20; -fx-padding: 4 10 4 10;");
        return pill;
    }

    private Label rateNoteLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font(FONT_UI, 11));
        l.setTextFill(Color.web(INK_SOFT));
        l.setWrapText(true);
        return l;
    }

    private Label idNoteLabel(String text) {
        Label l = new Label(text);
        l.setFont(Font.font(FONT_UI, 10));
        l.setTextFill(Color.web(INK_SOFT));
        return l;
    }

    private void showPopup(Node content, String windowTitle) {
        Stage popup = new Stage();
        popup.initOwner(primaryStage);
        popup.initModality(Modality.WINDOW_MODAL);
        popup.setTitle(windowTitle);
        popup.initStyle(StageStyle.UTILITY);

        Button close = new Button("\u2715  Close");
        close.setStyle(outlineBtnStyle());
        close.setOnAction(e -> popup.close());

        VBox root = new VBox(14, content, close);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: " + PAPER + ";");

        // subtle pop-in animation
        root.setOpacity(0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(280), content);
        slide.setFromY(-16); slide.setToY(0);
        FadeTransition fade = new FadeTransition(Duration.millis(280), root);
        fade.setFromValue(0); fade.setToValue(1);

        Scene scene = new Scene(root);
        popup.setScene(scene);
        popup.show();
        slide.play();
        fade.play();
    }

    /* ============================================================
       MEMBERS
       ============================================================ */
    private final ObjectProperty<VehicleType> memberVehicleType = new SimpleObjectProperty<>(VehicleType.CAR);
    private final ObjectProperty<Timeframe> memberTimeframe = new SimpleObjectProperty<>(Timeframe.MONTHLY);
    private final ObjectProperty<Status> memberStatus = new SimpleObjectProperty<>(Status.PAID);

    private void showMembers() {
        setActiveTab("Members");
        refreshListeners.clear();

        VBox head = (VBox) sectionHead("Members \u00b7 Floor B", "");
        Label headSub = (Label) head.getChildren().get(1);

        HBox vtRow = new HBox(10);
        Map<VehicleType, Button> vtButtons = new LinkedHashMap<>();
        for (VehicleType vt : new VehicleType[]{VehicleType.CAR, VehicleType.BIKE}) {
            Button b = vehicleTypeButton(vt);
            b.setPrefHeight(56);
            b.setOnAction(e -> {
                memberVehicleType.set(vt);
                for (Map.Entry<VehicleType, Button> en : vtButtons.entrySet())
                    styleVtButton(en.getValue(), en.getKey() == vt);
            });
            vtButtons.put(vt, b);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        vtRow.getChildren().addAll(vtButtons.values());
        styleVtButton(vtButtons.get(VehicleType.CAR), true);

        TextField regField = new TextField();
        regField.setPromptText("e.g. ABC-12-098");
        regField.setStyle(inputStyle() + " -fx-font-family: '" + FONT_MONO + "';");
        regField.textProperty().addListener((o, ov, nv) -> {
            String up = nv.toUpperCase();
            if (!up.equals(nv)) regField.setText(up);
        });

        ToggleButton monthlyBtn = new ToggleButton("Monthly");
        ToggleButton weeklyBtn = new ToggleButton("Weekly");
        ToggleGroup tfGroup = new ToggleGroup();
        monthlyBtn.setToggleGroup(tfGroup);
        weeklyBtn.setToggleGroup(tfGroup);
        monthlyBtn.setSelected(true);
        monthlyBtn.setMaxWidth(Double.MAX_VALUE);
        weeklyBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(monthlyBtn, Priority.ALWAYS);
        HBox.setHgrow(weeklyBtn, Priority.ALWAYS);
        HBox tfRow = new HBox(8, monthlyBtn, weeklyBtn);
        styleToggle(monthlyBtn); styleToggle(weeklyBtn);
        monthlyBtn.selectedProperty().addListener((o, ov, nv) -> { styleToggle(monthlyBtn); styleToggle(weeklyBtn); });
        weeklyBtn.selectedProperty().addListener((o, ov, nv) -> { styleToggle(monthlyBtn); styleToggle(weeklyBtn); });

        // Month picker (YearMonth via two combo-ish spinners) — simple: a ComboBox of next 12 months
        ComboBox<String> monthBox = new ComboBox<>();
        java.time.YearMonth base = java.time.YearMonth.now();
        List<java.time.YearMonth> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) months.add(base.plusMonths(i));
        for (java.time.YearMonth ym : months) monthBox.getItems().add(ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear());
        monthBox.getSelectionModel().selectFirst();
        monthBox.setMaxWidth(Double.MAX_VALUE);

        DatePicker weekStart = new DatePicker(java.time.LocalDate.now());
        weekStart.setMaxWidth(Double.MAX_VALUE);

        StackPane periodSwitcher = new StackPane(monthBox, weekStart);
        weekStart.setVisible(false);
        monthlyBtn.selectedProperty().addListener((o, ov, nv) -> {
            monthBox.setVisible(nv); weekStart.setVisible(!nv);
        });

        ToggleButton paidBtn = new ToggleButton("Paid");
        ToggleButton unpaidBtn = new ToggleButton("Unpaid");
        ToggleGroup stGroup = new ToggleGroup();
        paidBtn.setToggleGroup(stGroup);
        unpaidBtn.setToggleGroup(stGroup);
        paidBtn.setSelected(true);
        paidBtn.setMaxWidth(Double.MAX_VALUE);
        unpaidBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(paidBtn, Priority.ALWAYS);
        HBox.setHgrow(unpaidBtn, Priority.ALWAYS);
        HBox stRow = new HBox(8, paidBtn, unpaidBtn);
        styleToggle(paidBtn); styleToggle(unpaidBtn);
        paidBtn.selectedProperty().addListener((o, ov, nv) -> { styleToggle(paidBtn); styleToggle(unpaidBtn); });
        unpaidBtn.selectedProperty().addListener((o, ov, nv) -> { styleToggle(paidBtn); styleToggle(unpaidBtn); });

        GridPane formGrid = new GridPane();
        formGrid.setHgap(14); formGrid.setVgap(14);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setPercentWidth(50);
        ColumnConstraints c2 = new ColumnConstraints(); c2.setPercentWidth(50);
        formGrid.getColumnConstraints().addAll(c1, c2);
        formGrid.add(fieldBox(fieldLabel("Registration No."), regField), 0, 0);
        formGrid.add(fieldBox(fieldLabel("Time Frame"), tfRow), 1, 0);
        formGrid.add(fieldBox(fieldLabel("Month / Week"), periodSwitcher), 0, 1);
        formGrid.add(fieldBox(fieldLabel("Payment Status"), stRow), 1, 1);

        Button submit = new Button("\u25a2  Register Member & Issue Card");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setStyle(primaryBtnStyle());

        VBox listBox = new VBox(10);
        Runnable rebuildList = () -> {
            listBox.getChildren().clear();
            if (members.isEmpty()) {
                listBox.getChildren().add(emptyNote("No members registered yet."));
            }
            for (Member m : members) listBox.getChildren().add(memberRow(m));
            int free = freeSlots("B").size();
            headSub.setText(free + " of " + CAPACITY + " reserved slots free");
        };
        rebuildList.run();
        refreshListeners.add(rebuildList);

        submit.setOnAction(e -> {
            String reg = regField.getText() == null ? "" : regField.getText().trim().toUpperCase();
            if (reg.isEmpty()) { notify("Enter a registration number", false); return; }
            boolean exists = members.stream().anyMatch(m -> m.regNo.equals(reg));
            if (exists) { notify(reg + " is already a registered member", false); return; }
            Integer slot = pickRandomSlot("B");
            if (slot == null) { notify("Floor B is full (100/100)", false); return; }

            Timeframe tf = monthlyBtn.isSelected() ? Timeframe.MONTHLY : Timeframe.WEEKLY;
            String period;
            if (tf == Timeframe.MONTHLY) {
                period = monthBox.getValue();
            } else {
                java.time.LocalDate start = weekStart.getValue();
                java.time.LocalDate end = start.plusDays(6);
                java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
                period = "Week of " + start.format(f) + " - " + end.format(f) + ", " + end.getYear();
            }
            Status status = paidBtn.isSelected() ? Status.PAID : Status.UNPAID;
            String id = randomId();
            Member m = new Member(id, memberVehicleType.get(), reg, slot, tf, period, status);
            members.add(m);
            occupied.get("B").put(slot, new ParkedVehicle(id, reg, memberVehicleType.get(), LocalDateTime.now(), true));
            regField.clear();
            refresh();
            showMemberCardPopup(m);
        });
        memberVehicleType.set(VehicleType.CAR);

        VBox formPanel = new VBox(16, vtRow, formGrid, submit);
        formPanel.setPadding(new Insets(22));
        formPanel.setStyle("-fx-background-color: white; -fx-border-color: " + LINE + "; -fx-border-radius: 8; -fx-background-radius: 8;");

        Label listTitle = new Label("Registered Members");
        listTitle.setFont(Font.font(FONT_UI, FontWeight.BOLD, 15));
        VBox listHead = new VBox(listTitle);
        listHead.setPadding(new Insets(24, 0, 0, 0));

        Node page = container(head, formPanel, listHead, listBox);
        setContent(page);
    }

    private void styleToggle(ToggleButton b) {
        boolean sel = b.isSelected();
        b.setStyle("-fx-background-color: " + (sel ? RED : "white") + ";" +
                "-fx-text-fill: " + (sel ? "white" : INK) + ";" +
                "-fx-border-color: " + LINE + "; -fx-font-weight: bold; -fx-font-size: 13px;" +
                "-fx-background-radius: 4; -fx-border-radius: 4; -fx-padding: 9 0 9 0; -fx-cursor: hand;");
    }

    private Node emptyNote(String text) {
        Label l = new Label(text);
        l.setFont(Font.font(FONT_UI, 13));
        l.setTextFill(Color.web(INK_SOFT));
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        l.setPadding(new Insets(16));
        l.setStyle("-fx-background-color: white; -fx-border-color: " + LINE + "; -fx-border-style: dashed; -fx-border-radius: 6; -fx-background-radius: 6;");
        return l;
    }

    private HBox memberRow(Member m) {
        Label icon = new Label(m.vehicleType == VehicleType.CAR ? "\ud83d\ude97" : "\ud83d\udeb2");
        icon.setFont(Font.font(16));

        Label reg = new Label(m.regNo);
        reg.setFont(Font.font(FONT_MONO, FontWeight.BOLD, 14));
        reg.setTextFill(Color.web(INK));
        Label sub = new Label("Slot B-" + m.slot + " \u00b7 " + m.timeframe.name() + " \u00b7 " + m.period);
        sub.setFont(Font.font(FONT_UI, 12));
        sub.setTextFill(Color.web(INK_SOFT));
        VBox mainBox = new VBox(2, reg, sub);
        HBox.setHgrow(mainBox, Priority.ALWAYS);

        Label pill = statusPill(m.status.get());

        Button toggleBtn = new Button("Toggle");
        toggleBtn.setStyle(miniBtnStyle(false));
        toggleBtn.setOnAction(e -> {
            m.status.set(m.status.get() == Status.PAID ? Status.UNPAID : Status.PAID);
            refresh();
        });

        Button cardBtn = new Button("Card");
        cardBtn.setStyle(miniBtnStyle(false));
        cardBtn.setOnAction(e -> showMemberCardPopup(m));

        Button endBtn = new Button("End");
        endBtn.setStyle(miniBtnStyle(true));
        endBtn.setOnAction(e -> {
            members.remove(m);
            occupied.get("B").remove(m.slot);
            refresh();
            notify("Membership ended, slot released", true);
        });

        HBox row = new HBox(12, icon, mainBox, pill, toggleBtn, cardBtn, endBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 14, 12, 14));
        row.setStyle("-fx-background-color: white; -fx-border-color: " + LINE + "; -fx-border-radius: 6; -fx-background-radius: 6;");
        return row;
    }

    private String miniBtnStyle(boolean danger) {
        return "-fx-background-color: white; -fx-border-color: " + (danger ? RED : LINE) + ";" +
               "-fx-text-fill: " + (danger ? RED : INK) + "; -fx-font-size: 11px; -fx-font-weight: bold;" +
               "-fx-padding: 6 10 6 10; -fx-background-radius: 4; -fx-border-radius: 4; -fx-cursor: hand;";
    }

    /* ============================================================
       CHECKOUT
       ============================================================ */
    private void showCheckout() {
        setActiveTab("Checkout");
        refreshListeners.clear();

        VBox head = (VBox) sectionHead("Checkout", "");
        Label headSub = (Label) head.getChildren().get(1);

        TextField search = new TextField();
        search.setPromptText("Search by registration number");
        search.setStyle(inputStyle());
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox searchRow = new HBox(8, new Label("\ud83d\udd0d"), search);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.setPadding(new Insets(8, 12, 8, 12));
        searchRow.setStyle("-fx-background-color: white; -fx-border-color: " + LINE + "; -fx-border-radius: 5; -fx-background-radius: 5;");

        VBox listBox = new VBox(10);

        Runnable rebuild = () -> {
            List<Map.Entry<String, Map.Entry<Integer, ParkedVehicle>>> rows = new ArrayList<>();
            for (String floorId : new String[]{"A", "C", "D"}) {
                for (Map.Entry<Integer, ParkedVehicle> en : occupied.get(floorId).entrySet()) {
                    rows.add(Map.entry(floorId, en));
                }
            }
            rows.sort((a, b) -> b.getValue().getValue().entryTime.compareTo(a.getValue().getValue().entryTime));
            String q = search.getText() == null ? "" : search.getText().trim().toUpperCase();

            listBox.getChildren().clear();
            long shown = 0;
            for (var row : rows) {
                String floorId = row.getKey();
                int slot = row.getValue().getKey();
                ParkedVehicle pv = row.getValue().getValue();
                if (!q.isEmpty() && !pv.regNo.contains(q)) continue;
                shown++;
                listBox.getChildren().add(checkoutRow(floorId, slot, pv));
            }
            if (shown == 0) listBox.getChildren().add(emptyNote("No matching vehicles."));
            headSub.setText(rows.size() + " non-member vehicles currently parked");
        };
        rebuild.run();
        refreshListeners.add(rebuild);
        search.textProperty().addListener((o, ov, nv) -> rebuild.run());

        Node page = container(head, searchRow, listBox);
        setContent(page);
    }

    private HBox checkoutRow(String floorId, int slot, ParkedVehicle pv) {
        Label icon = new Label(pv.vehicleType == VehicleType.CAR ? "\ud83d\ude97" : pv.vehicleType == VehicleType.BIKE ? "\ud83d\udeb2" : "\ud83d\ude9b");
        icon.setFont(Font.font(16));

        Label reg = new Label(pv.regNo);
        reg.setFont(Font.font(FONT_MONO, FontWeight.BOLD, 14));
        Label sub = new Label("Floor " + floorId + " \u00b7 Slot " + slot + " \u00b7 In: " + pv.entryTime.format(DTF));
        sub.setFont(Font.font(FONT_UI, 12));
        sub.setTextFill(Color.web(INK_SOFT));
        VBox mainBox = new VBox(2, reg, sub);
        HBox.setHgrow(mainBox, Priority.ALWAYS);

        Fee fee = calcFee(pv.vehicleType, pv.entryTime, LocalDateTime.now());
        Label hoursLbl = new Label(fee.hours() + "h");
        hoursLbl.setFont(Font.font(FONT_UI, 11));
        hoursLbl.setTextFill(Color.web(INK_SOFT));
        Label amtLbl = new Label("Rs " + fee.amount());
        amtLbl.setFont(Font.font(FONT_UI, FontWeight.EXTRA_BOLD, 15));
        amtLbl.setTextFill(Color.web(RED));
        VBox feeBox = new VBox(2, hoursLbl, amtLbl);
        feeBox.setAlignment(Pos.CENTER_RIGHT);
        feeBox.setPrefWidth(80);

        Button checkoutBtn = new Button("\ud83d\udda8  Checkout");
        checkoutBtn.setStyle(primaryBtnStyle().replace("12 18 12 18", "8 14 8 14") + " -fx-font-size: 12px;");
        checkoutBtn.setOnAction(e -> {
            occupied.get(floorId).remove(slot);
            Fee f = calcFee(pv.vehicleType, pv.entryTime, LocalDateTime.now());
            refresh();
            notify(pv.regNo + " checked out \u00b7 " + f.hours() + "h \u00b7 Rs " + f.amount(), true);
        });

        HBox row = new HBox(12, icon, mainBox, feeBox, checkoutBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(12, 14, 12, 14));
        row.setStyle("-fx-background-color: white; -fx-border-color: " + LINE + "; -fx-border-radius: 6; -fx-background-radius: 6;");
        return row;
    }
}