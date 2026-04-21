import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class UltimateShooter extends JPanel implements ActionListener, KeyListener, MouseListener {

    // Размеры окна
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;

    // Состояния игры
    private boolean running = true;
    private boolean paused = false;
    private boolean gameOver = false;
    private boolean bossActive = false;

    // Игрок
    private Player player;

    // Списки объектов
    private final List<Bullet> bullets = new ArrayList<>();
    private final List<Enemy> enemies = new ArrayList<>();
    private final List<PowerUp> powerUps = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<EnemyBullet> enemyBullets = new ArrayList<>();

    // Таймеры и генерация
    private final Timer timer;
    private final Random rand = new Random();
    private int enemySpawnTimer = 0;
    private int baseSpawnDelay = 40;
    private int score = 0;
    private int level = 1;
    private int enemiesKilled = 0;
    private int enemiesToNextBoss = 20; // каждые 20 убитых - босс

    // Управление
    private boolean left, right, space;
    private int shootCooldown = 0;
    private final int BASE_SHOOT_DELAY = 12;

    // Рекорд
    private int highScore = 0;
    private static final String HIGHSCORE_FILE = "highscore.dat";

    // Звуки и музыка
    private Sound shootSound;
    private Sound explosionSound;
    private Sound powerupSound;
    private Sound hitSound;
    private MusicPlayer bgMusic;

    // Кнопки меню паузы (координаты)
    private final Rectangle resumeBtn = new Rectangle(WIDTH/2-75, HEIGHT/2-40, 150, 40);
    private final Rectangle restartBtn = new Rectangle(WIDTH/2-75, HEIGHT/2+10, 150, 40);
    private final Rectangle exitBtn = new Rectangle(WIDTH/2-75, HEIGHT/2+60, 150, 40);

    public UltimateShooter() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(this);

        player = new Player(WIDTH / 2 - 25, HEIGHT - 80);

        initSounds();
        loadHighScore();

        timer = new Timer(16, this); // ~60 FPS
        timer.start();

        // Фоновая музыка (если есть файл)
        bgMusic = new MusicPlayer("bg_music.wav");
        bgMusic.playLoop();
    }

    private void initSounds() {
        shootSound = new Sound("shoot.wav");
        explosionSound = new Sound("explosion.wav");
        powerupSound = new Sound("powerup.wav");
        hitSound = new Sound("hit.wav");
    }

    private void loadHighScore() {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(HIGHSCORE_FILE))) {
            highScore = dis.readInt();
        } catch (Exception e) {
            highScore = 0;
        }
    }

    private void saveHighScore() {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(HIGHSCORE_FILE))) {
            dos.writeInt(highScore);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // === Игровая логика ===
    private void update() {
        if (paused || gameOver || !running) return;

        // Движение игрока
        if (left) player.moveLeft();
        if (right) player.moveRight(WIDTH);

        // Стрельба
        if (space) {
            int currentDelay = Math.max(5, BASE_SHOOT_DELAY - player.getFireRateBonus());
            if (shootCooldown <= 0) {
                if (player.weaponType == 0) {
                    // Лазер
                    bullets.add(new Bullet(player.getCenterX() - 2, player.y, 0));
                } else {
                    // Дробовик
                    bullets.add(new Bullet(player.getCenterX() - 2, player.y, -2));
                    bullets.add(new Bullet(player.getCenterX() - 2, player.y, 0));
                    bullets.add(new Bullet(player.getCenterX() - 2, player.y, 2));
                }
                shootSound.play();
                shootCooldown = currentDelay;
            }
        }
        if (shootCooldown > 0) shootCooldown--;

        // Обновление таймеров игрока
        player.updateTimers();

        // Обновление объектов
        updateBullets();
        updateEnemyBullets();
        updateEnemies();
        updatePowerUps();
        updateParticles();

        // Проверка столкновений
        checkCollisions();

        // Спавн врагов или босса
        if (!bossActive) {
            spawnEnemies();
        }

        // Проверка необходимости появления босса
        if (!bossActive && enemiesKilled >= enemiesToNextBoss) {
            spawnBoss();
            enemiesToNextBoss += 20; // следующий босс через ещё 20 убийств
        }

        // Повышение уровня сложности
        adjustDifficulty();
    }

    private void updateBullets() {
        Iterator<Bullet> it = bullets.iterator();
        while (it.hasNext()) {
            Bullet b = it.next();
            b.move();
            if (b.y < 0 || b.y > HEIGHT || b.x < 0 || b.x > WIDTH) it.remove();
        }
    }

    private void updateEnemyBullets() {
        Iterator<EnemyBullet> it = enemyBullets.iterator();
        while (it.hasNext()) {
            EnemyBullet eb = it.next();
            eb.move();
            if (eb.y > HEIGHT || eb.y < 0 || eb.x < 0 || eb.x > WIDTH) it.remove();
        }
    }

    private void updateEnemies() {
        Iterator<Enemy> it = enemies.iterator();
        while (it.hasNext()) {
            Enemy e = it.next();
            e.move();
            if (e instanceof BossEnemy) {
                ((BossEnemy) e).updateShoot(enemyBullets, player);
            }
            if (e.y > HEIGHT + 100) {
                if (e instanceof BossEnemy) bossActive = false;
                it.remove();
            }
        }
    }

    private void updatePowerUps() {
        Iterator<PowerUp> it = powerUps.iterator();
        while (it.hasNext()) {
            PowerUp p = it.next();
            p.move();
            if (p.y > HEIGHT) it.remove();
        }
    }

    private void updateParticles() {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.update();
            if (p.isDead()) it.remove();
        }
    }

    private void checkCollisions() {
        // Пули игрока против врагов
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);
            Rectangle bRect = b.getBounds();
            for (int j = enemies.size() - 1; j >= 0; j--) {
                Enemy e = enemies.get(j);
                if (bRect.intersects(e.getBounds())) {
                    bullets.remove(i);
                    e.hit();
                    if (e.isDead()) {
                        enemies.remove(j);
                        score += e.scoreValue;
                        enemiesKilled++;
                        if (e instanceof BossEnemy) bossActive = false;
                        // Шанс выпадения бонуса
                        if (rand.nextDouble() < 0.3) {
                            powerUps.add(new PowerUp(e.x + e.width/2 - 10, e.y, rand.nextInt(3)));
                        }
                        addExplosion(e.getCenterX(), e.getCenterY());
                        explosionSound.play();
                    } else {
                        hitSound.play();
                    }
                    break;
                }
            }
        }

        // Вражеские пули против игрока
        Rectangle playerRect = player.getBounds();
        for (int i = enemyBullets.size() - 1; i >= 0; i--) {
            EnemyBullet eb = enemyBullets.get(i);
            if (playerRect.intersects(eb.getBounds())) {
                enemyBullets.remove(i);
                player.takeDamage();
                if (!player.isAlive()) {
                    gameOver = true;
                    if (score > highScore) {
                        highScore = score;
                        saveHighScore();
                    }
                }
                player.activateShield(60);
                hitSound.play();
            }
        }

        // Игрок против врагов
        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy e = enemies.get(i);
            if (playerRect.intersects(e.getBounds())) {
                enemies.remove(i);
                if (e instanceof BossEnemy) bossActive = false;
                addExplosion(e.getCenterX(), e.getCenterY());
                explosionSound.play();
                player.takeDamage();
                if (!player.isAlive()) {
                    gameOver = true;
                    if (score > highScore) {
                        highScore = score;
                        saveHighScore();
                    }
                }
                player.activateShield(60);
            }
        }

        // Игрок против бонусов
        for (int i = powerUps.size() - 1; i >= 0; i--) {
            PowerUp p = powerUps.get(i);
            if (playerRect.intersects(p.getBounds())) {
                applyPowerUp(p.type);
                powerUps.remove(i);
                powerupSound.play();
            }
        }
    }

    private void applyPowerUp(int type) {
        switch (type) {
            case 0: player.heal(); break;
            case 1: player.addFireRateBonus(3, 300); break;
            case 2: player.activateShield(180); break;
        }
    }

    private void spawnEnemies() {
        if (enemySpawnTimer <= 0) {
            int x = rand.nextInt(WIDTH - 40);
            int type = rand.nextInt(10);
            Enemy e;
            if (type < 2) e = new FastEnemy(x, -30);
            else if (type < 4) e = new ToughEnemy(x, -30);
            else e = new BasicEnemy(x, -30);
            enemies.add(e);
            enemySpawnTimer = baseSpawnDelay;
        } else {
            enemySpawnTimer--;
        }
    }

    private void spawnBoss() {
        bossActive = true;
        BossEnemy boss = new BossEnemy(WIDTH/2 - 50, -100);
        enemies.add(boss);
    }

    private void adjustDifficulty() {
        int newLevel = enemiesKilled / 10 + 1;
        if (newLevel > level) {
            level = newLevel;
            baseSpawnDelay = Math.max(15, 40 - level * 2);
        }
    }

    private void addExplosion(int x, int y) {
        for (int i = 0; i < 15; i++) {
            particles.add(new Particle(x, y, Color.ORANGE));
        }
    }

    // === Графика ===
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawStars(g2d);

        player.draw(g2d);
        for (Bullet b : bullets) b.draw(g2d);
        for (Enemy e : enemies) e.draw(g2d);
        for (PowerUp p : powerUps) p.draw(g2d);
        for (Particle p : particles) p.draw(g2d);
        for (EnemyBullet eb : enemyBullets) eb.draw(g2d);

        drawUI(g2d);

        if (paused) {
            g2d.setColor(new Color(0, 0, 0, 180));
            g2d.fillRect(0, 0, WIDTH, HEIGHT);
            drawPauseMenu(g2d);
        }

        if (gameOver) {
            g2d.setColor(new Color(0, 0, 0, 180));
            g2d.fillRect(0, 0, WIDTH, HEIGHT);
            g2d.setColor(Color.RED);
            g2d.setFont(new Font("Arial", Font.BOLD, 48));
            drawCenteredString(g2d, "GAME OVER", WIDTH/2, HEIGHT/2 - 60);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Arial", Font.PLAIN, 24));
            drawCenteredString(g2d, "Score: " + score + "   Level: " + level, WIDTH/2, HEIGHT/2 - 10);
            drawCenteredString(g2d, "High Score: " + highScore, WIDTH/2, HEIGHT/2 + 20);
            drawCenteredString(g2d, "Press R to restart", WIDTH/2, HEIGHT/2 + 60);
        }
    }

    private void drawStars(Graphics2D g) {
        g.setColor(Color.WHITE);
        for (int i = 0; i < 100; i++) {
            int x = (i * 37) % WIDTH;
            int y = (i * 83 + (int)(System.currentTimeMillis() * 0.02)) % HEIGHT;
            g.fillRect(x, y, 2, 2);
        }
    }

    private void drawUI(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 16));
        g.drawString("Score: " + score, 10, 25);
        g.drawString("Level: " + level, 10, 45);
        g.drawString("Lives: " + player.getLives(), 10, 65);
        g.drawString("High Score: " + highScore, 10, 85);
        if (player.hasShield()) {
            g.setColor(Color.CYAN);
            g.drawString("SHIELD", 10, 105);
        }
        if (player.getFireRateBonus() > 0) {
            g.setColor(Color.YELLOW);
            g.drawString("RAPID FIRE", 10, 125);
        }
        g.setColor(Color.WHITE);
        g.drawString("Weapon: " + (player.weaponType == 0 ? "Laser" : "Shotgun"), 10, 145);
        if (bossActive) {
            g.setColor(Color.RED);
            g.drawString("BOSS BATTLE!", WIDTH - 150, 25);
        }
    }

    private void drawPauseMenu(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 36));
        drawCenteredString(g, "PAUSED", WIDTH/2, HEIGHT/2 - 100);

        // Кнопки
        drawButton(g, resumeBtn, "Resume (P)");
        drawButton(g, restartBtn, "Restart (R)");
        drawButton(g, exitBtn, "Exit (ESC)");
    }

    private void drawButton(Graphics2D g, Rectangle rect, String text) {
        g.setColor(Color.DARK_GRAY);
        g.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
        g.setColor(Color.WHITE);
        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
        g.setFont(new Font("Arial", Font.BOLD, 18));
        FontMetrics fm = g.getFontMetrics();
        int textX = rect.x + (rect.width - fm.stringWidth(text)) / 2;
        int textY = rect.y + ((rect.height - fm.getHeight()) / 2) + fm.getAscent();
        g.drawString(text, textX, textY);
    }

    private void drawCenteredString(Graphics g, String text, int x, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x - fm.stringWidth(text)/2, y);
    }

    // === Управление и события ===
    @Override
    public void actionPerformed(ActionEvent e) {
        if (running && !gameOver && !paused) {
            update();
        }
        repaint();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.VK_LEFT:  left = true; break;
            case KeyEvent.VK_RIGHT: right = true; break;
            case KeyEvent.VK_SPACE: space = true; break;
            case KeyEvent.VK_E:
                if (!paused && !gameOver) {
                    player.weaponType = (player.weaponType + 1) % 2;
                }
                break;
            case KeyEvent.VK_P:
                if (!gameOver) {
                    paused = !paused;
                    if (!paused) requestFocusInWindow();
                }
                break;
            case KeyEvent.VK_R:
                if (gameOver || paused) {
                    restartGame();
                }
                break;
            case KeyEvent.VK_ESCAPE:
                if (paused) {
                    System.exit(0);
                }
                break;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.VK_LEFT:  left = false; break;
            case KeyEvent.VK_RIGHT: right = false; break;
            case KeyEvent.VK_SPACE: space = false; break;
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    // Мышь для меню паузы
    @Override
    public void mouseClicked(MouseEvent e) {
        if (!paused) return;
        Point p = e.getPoint();
        if (resumeBtn.contains(p)) {
            paused = false;
            requestFocusInWindow();
        } else if (restartBtn.contains(p)) {
            restartGame();
        } else if (exitBtn.contains(p)) {
            System.exit(0);
        }
    }

    @Override public void mousePressed(MouseEvent e) {}
    @Override public void mouseReleased(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    private void restartGame() {
        gameOver = false;
        paused = false;
        bossActive = false;
        score = 0;
        level = 1;
        enemiesKilled = 0;
        enemiesToNextBoss = 20;
        baseSpawnDelay = 40;
        player = new Player(WIDTH / 2 - 25, HEIGHT - 80);
        bullets.clear();
        enemies.clear();
        powerUps.clear();
        particles.clear();
        enemyBullets.clear();
        shootCooldown = 0;
        requestFocusInWindow();
    }

    // === Внутренние классы ===

    class Player {
        int x, y;
        final int width = 50, height = 20;
        private int lives = 3;
        private int shieldTimer = 0;
        private int fireRateBonus = 0;
        private int fireBonusTimer = 0;
        private boolean invulnerable = false;
        private int invulTimer = 0;
        int weaponType = 0; // 0 - лазер, 1 - дробовик

        Player(int x, int y) { this.x = x; this.y = y; }

        void moveLeft() { x = Math.max(0, x - 5); }
        void moveRight(int limit) { x = Math.min(limit - width, x + 5); }

        int getCenterX() { return x + width/2; }

        Rectangle getBounds() { return new Rectangle(x, y, width, height); }

        void draw(Graphics2D g) {
            // Мерцание при неуязвимости
            if (invulnerable && (System.currentTimeMillis() / 100) % 2 == 0) {
                return;
            }
            if (shieldTimer > 0) {
                g.setColor(Color.CYAN);
                g.fillRoundRect(x-2, y-2, width+4, height+4, 5, 5);
            }
            g.setColor(Color.GREEN);
            g.fillRoundRect(x, y, width, height, 5, 5);
            g.setColor(Color.GRAY);
            g.fillRect(x + width/2 - 3, y - 8, 6, 8);
            // Индикатор оружия
            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.BOLD, 10));
            g.drawString(weaponType == 0 ? "LASER" : "SHOTGUN", x, y-10);
        }

        void takeDamage() {
            if (shieldTimer <= 0 && !invulnerable) {
                lives--;
                invulnerable = true;
                invulTimer = 90; // 1.5 сек
            }
        }

        boolean isAlive() { return lives > 0; }
        int getLives() { return lives; }
        void heal() { if (lives < 5) lives++; }

        void activateShield(int duration) { shieldTimer = duration; }
        boolean hasShield() { return shieldTimer > 0; }

        void addFireRateBonus(int amount, int duration) {
            fireRateBonus = amount;
            fireBonusTimer = duration;
        }

        int getFireRateBonus() {
            if (fireBonusTimer > 0) {
                return fireRateBonus;
            }
            return 0;
        }

        void updateTimers() {
            if (shieldTimer > 0) shieldTimer--;
            if (fireBonusTimer > 0) fireBonusTimer--;
            if (invulTimer > 0) {
                invulTimer--;
                if (invulTimer == 0) invulnerable = false;
            }
        }
    }

    class Bullet {
        double x, y;
        double vx, vy;
        final int size = 6;

        Bullet(double x, double y, double angleOffset) {
            this.x = x;
            this.y = y;
            double angle = -Math.PI/2 + angleOffset * 0.1;
            double speed = 10;
            vx = Math.cos(angle) * speed;
            vy = Math.sin(angle) * speed;
        }

        void move() { x += vx; y += vy; }

        Rectangle getBounds() { return new Rectangle((int)x, (int)y, size, size*2); }

        void draw(Graphics2D g) {
            g.setColor(Color.YELLOW);
            g.fillOval((int)x, (int)y, size, size*2);
        }
    }

    class EnemyBullet {
        double x, y;
        double vx, vy;
        final int size = 8;

        EnemyBullet(double x, double y, double targetX, double targetY) {
            this.x = x;
            this.y = y;
            double angle = Math.atan2(targetY - y, targetX - x);
            double speed = 5;
            vx = Math.cos(angle) * speed;
            vy = Math.sin(angle) * speed;
        }

        void move() { x += vx; y += vy; }

        Rectangle getBounds() { return new Rectangle((int)x, (int)y, size, size); }

        void draw(Graphics2D g) {
            g.setColor(Color.RED);
            g.fillOval((int)x, (int)y, size, size);
        }
    }

    abstract class Enemy {
        int x, y;
        int width = 40, height = 20;
        int health;
        int speed;
        int scoreValue;

        Enemy(int x, int y, int health, int speed, int score) {
            this.x = x; this.y = y;
            this.health = health;
            this.speed = speed;
            this.scoreValue = score;
        }

        void move() { y += speed; }

        Rectangle getBounds() { return new Rectangle(x, y, width, height); }

        int getCenterX() { return x + width/2; }
        int getCenterY() { return y + height/2; }

        void hit() { health--; }
        boolean isDead() { return health <= 0; }

        abstract void draw(Graphics2D g);
    }

    class BasicEnemy extends Enemy {
        BasicEnemy(int x, int y) { super(x, y, 1, 3, 10); }
        void draw(Graphics2D g) {
            g.setColor(Color.RED);
            g.fillRect(x, y, width, height);
        }
    }

    class FastEnemy extends Enemy {
        FastEnemy(int x, int y) { super(x, y, 1, 5, 15); }
        void draw(Graphics2D g) {
            g.setColor(Color.MAGENTA);
            g.fillRect(x, y, width, height);
            g.setColor(Color.WHITE);
            g.drawLine(x+5, y+5, x+width-5, y+height-5);
        }
    }

    class ToughEnemy extends Enemy {
        ToughEnemy(int x, int y) { super(x, y, 3, 2, 30); }
        void draw(Graphics2D g) {
            g.setColor(Color.ORANGE);
            g.fillRect(x, y, width, height);
            g.setColor(Color.BLACK);
            g.drawString(""+health, x+15, y+15);
        }
    }

    class BossEnemy extends Enemy {
        private int moveDirection = 1;
        private int shootTimer = 0;
        private final int shootDelay = 40;

        BossEnemy(int x, int y) {
            super(x, y, 20, 2, 200);
            width = 100;
            height = 60;
        }

        @Override
        void move() {
            x += speed * moveDirection;
            if (x <= 0 || x >= WIDTH - width) {
                moveDirection *= -1;
            }
            // Не уходит за нижний край
            if (y < 50) y += 1;
        }

        void updateShoot(List<EnemyBullet> eBullets, Player player) {
            if (shootTimer <= 0) {
                eBullets.add(new EnemyBullet(getCenterX(), getCenterY(), player.getCenterX(), player.y));
                shootTimer = shootDelay;
            } else {
                shootTimer--;
            }
        }

        @Override
        void draw(Graphics2D g) {
            g.setColor(Color.RED.darker());
            int[] xPoints = {x + width/2, x + width, x + width/2, x};
            int[] yPoints = {y, y + height/2, y + height, y + height/2};
            g.fillPolygon(xPoints, yPoints, 4);
            // Полоса здоровья
            g.setColor(Color.GREEN);
            g.fillRect(x, y - 10, width * health / 20, 5);
            g.setColor(Color.WHITE);
            g.drawRect(x, y - 10, width, 5);
        }
    }

    class PowerUp {
        int x, y;
        int type;
        int speed = 2;
        int size = 15;

        PowerUp(int x, int y, int type) {
            this.x = x;
            this.y = y;
            this.type = type;
        }

        void move() { y += speed; }

        Rectangle getBounds() { return new Rectangle(x, y, size, size); }

        void draw(Graphics2D g) {
            switch (type) {
                case 0: g.setColor(Color.GREEN); break;
                case 1: g.setColor(Color.YELLOW); break;
                case 2: g.setColor(Color.CYAN); break;
            }
            g.fillOval(x, y, size, size);
            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.BOLD, 10));
            String label = type == 0 ? "❤" : type == 1 ? "⚡" : "🛡";
            g.drawString(label, x+3, y+12);
        }
    }

    class Particle {
        double x, y;
        double vx, vy;
        int life = 30;
        Color color;

        Particle(int x, int y, Color c) {
            this.x = x;
            this.y = y;
            this.color = c;
            double angle = rand.nextDouble() * 2 * Math.PI;
            double speed = rand.nextDouble() * 5 + 2;
            vx = Math.cos(angle) * speed;
            vy = Math.sin(angle) * speed;
        }

        void update() {
            x += vx;
            y += vy;
            vy += 0.1;
            life--;
        }

        boolean isDead() { return life <= 0; }

        void draw(Graphics2D g) {
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), life*8));
            g.fillRect((int)x, (int)y, 3, 3);
        }
    }

    // Звуки
    static class Sound {
        private Clip clip;
        Sound(String filename) {
            try {
                AudioInputStream ais = AudioSystem.getAudioInputStream(new File(filename));
                clip = AudioSystem.getClip();
                clip.open(ais);
            } catch (Exception e) {
                // Файл не найден – без звука
            }
        }
        void play() {
            if (clip != null) {
                clip.setFramePosition(0);
                clip.start();
            }
        }
    }

    static class MusicPlayer {
        private Clip clip;
        MusicPlayer(String filename) {
            try {
                AudioInputStream ais = AudioSystem.getAudioInputStream(new File(filename));
                clip = AudioSystem.getClip();
                clip.open(ais);
            } catch (Exception e) {
                // Без музыки
            }
        }
        void playLoop() {
            if (clip != null) {
                clip.loop(Clip.LOOP_CONTINUOUSLY);
            }
        }
        void stop() {
            if (clip != null) clip.stop();
        }
    }

    // === Запуск ===
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Ultimate Shooter");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            UltimateShooter game = new UltimateShooter();
            frame.add(game);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            game.requestFocusInWindow();
        });
    }
}