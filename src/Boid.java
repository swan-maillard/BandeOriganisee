import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;

public class Boid {

    private final int MAX_TRAILS = 50;
    private final double BOID_SIZE = 10;
    private final double DEAD_ANGLE = 45;
    private final double REPULSE_RANGE = 20;

    private Vector2D location;
    private Vector2D velocity;
    private Vector2D forces;
    private ArrayList<Vector2D> trails;
    private ArrayList<Boid> strangerNeighbours;
    private ArrayList<Boid> flockNeighbours;

    private Flock flock;


    public Boid(Flock flock) {
        this(flock, 0, 0);
        setRandomLocation();
    }

    public Boid(Flock flock, double x, double y) {
        this.flock = flock;
        location = new Vector2D(x, y);
        setRandomVelocity();
        forces = new Vector2D();
        trails = new ArrayList<>(MAX_TRAILS);
    }

    public void setRandomLocation() {
        location = new Vector2D(Math.random() * AppView.SIMULATION_PANEL_WIDTH, Math.random() * AppView.HEIGHT);
    }

    public void setRandomVelocity() {
        do {
            velocity = new Vector2D(Math.random() * 2 * flock.getSpeedLimit() - flock.getSpeedLimit(), Math.random() * 2 * flock.getSpeedLimit() - flock.getSpeedLimit());
        } while (velocity.norm() < App.BOIDS_MIN_SPEED);
    }

    private void findNeighbours() {
        flockNeighbours = new ArrayList<>();
        strangerNeighbours = new ArrayList<>();


        for (Flock fl : App.flocks) {
            for (Boid boid : fl.getBoids()) {

                Vector2D vectorBetweenBoids = Vector2D.subtract(boid.location, this.location);
                double angleBoid = Math.atan2(velocity.y, velocity.x);
                double angleBetweenBoids = Math.abs(Math.atan2(vectorBetweenBoids.y, vectorBetweenBoids.x));
                double deadAngleRadian = Math.PI * DEAD_ANGLE / 180;

                boolean isBoidInDeadAngle = (angleBetweenBoids - angleBoid >= Math.PI - deadAngleRadian / 2 && angleBetweenBoids - angleBoid <= Math.PI + deadAngleRadian / 2);

                if (boid != this && vectorBetweenBoids.norm() <= flock.getViewRange() && !isBoidInDeadAngle) {
                    if (fl == this.flock) {
                        flockNeighbours.add(boid);
                    } else {
                        strangerNeighbours.add(boid);
                    }
                }
            }
        }
    }

    private void computeForces() {
        forces.multiply(0);

        findNeighbours();

        computeCohesionForce();
        computeSeparationForce();
        computeAlignementForce();

        if (flock.isPredator) {
            computeHuntingForce();
        } else {
            computeIntoleranceForce();
            computeFleeingForce();
        }

        computeObstacleAvoidanceForce();
        computeWallAvoidanceForce();

        applyForces();
    }

    private void computeCohesionForce() {
        Vector2D cohesionForce = new Vector2D();
        Vector2D flockCenter = new Vector2D();
        for (Boid boid : flockNeighbours) {
            flockCenter.add(boid.location);
        }

        if (!flockNeighbours.isEmpty()) {
            flockCenter.divide(flockNeighbours.size());
            cohesionForce = Vector2D.subtract(flockCenter, location).subtract(velocity).multiply(flock.getCohesionCoeff());
        }
        forces.add(cohesionForce);
    }

    private void computeIntoleranceForce() {
        Vector2D intoleranceForce = new Vector2D();

        for (Boid boid : strangerNeighbours) {
            Vector2D force = new Vector2D(boid.velocity);
            intoleranceForce.add(force.multiply(-1));
        }
        if (intoleranceForce.norm() > 0) {
            intoleranceForce.divide(strangerNeighbours.size());
        }
        intoleranceForce.multiply(flock.getIntoleranceCoeff());
        forces.add(intoleranceForce);
    }

    private void computeSeparationForce() {
        Vector2D separationForce = new Vector2D();

        ArrayList<Boid> neighbours = new ArrayList<>();
        neighbours.addAll(flockNeighbours);
        neighbours.addAll(strangerNeighbours);
        for (Boid boid : neighbours) {
            if (Vector2D.subtract(this.location, boid.location).norm() <= REPULSE_RANGE) {
                separationForce = Vector2D.subtract(this.location, boid.location).subtract(velocity).multiply(flock.getSeparationCoeff());
            }
        }
        forces.add(separationForce);
    }

    private void computeAlignementForce() {
        Vector2D alignmentForce = new Vector2D();
        for (Boid boid : flockNeighbours) {
            alignmentForce.add(boid.velocity);
        }
        if (alignmentForce.norm() > 0) {
            alignmentForce.divide(flockNeighbours.size());
        }
        alignmentForce.multiply(flock.getAlignementCoeff());
        forces.add(alignmentForce);
    }

    private void computeHuntingForce() {
        Vector2D huntingForce = new Vector2D();

        Vector2D preysFlockCenter = new Vector2D();
        int nbPreys = 0;
        for (Boid boid : strangerNeighbours) {
            if (!boid.flock.isPredator) {
                preysFlockCenter.add(boid.location);
                nbPreys++;
            }
        }
        if (nbPreys > 0) {
            preysFlockCenter.divide(nbPreys);
            huntingForce.add(Vector2D.subtract(preysFlockCenter, location)).multiply(flock.getHuntingCoeff());
        }
        forces.add(huntingForce);
    }

    private void computeFleeingForce() {
        Vector2D fleeingForce = new Vector2D();

        Boid closestPredator = null;
        for (Boid boid : strangerNeighbours) {
            if (boid.flock.isPredator) {
                double boidDistance = Vector2D.subtract(boid.location, this.location).norm();
                if (closestPredator == null ||  boidDistance < Vector2D.subtract(closestPredator.location, this.location).norm()) {
                    closestPredator = boid;
                }
            }
        }

        if (closestPredator != null) {
            fleeingForce.add(Vector2D.subtract(this.location, closestPredator.location));
        }
        forces.add(fleeingForce);
    }

    private void computeObstacleAvoidanceForce() {
        for (Obstacle obstacle : App.obstacles) {
            if (willCollideObstacle(obstacle)) {
                Vector2D BO = Vector2D.subtract(obstacle.position, this.location); // Vecteur entre le boid et le centre de l'obstacle
                double projection = Math.sqrt(Math.pow(BO.norm(), 2) - Math.pow(obstacle.avoidanceRadius, 2)); // Projection de BO sur une direction qui ne traverse pas l'obstacle

                double m;
                if (BO.norm() >= obstacle.avoidanceRadius) {
                    m = (Math.pow(projection, 2) - Math.pow(obstacle.avoidanceRadius, 2) - Math.pow(BO.norm(), 2)) / (-2 * BO.norm());
                } else {
                    m = BO.norm();
                }

                double q = Math.sqrt(Math.pow(obstacle.avoidanceRadius, 2) - Math.pow(m, 2));

                Vector2D U = BO.normalized(); // Vecteur unitaire entre le boid et le centre de l'obstacle
                Vector2D V = velocity.normalized(); // Vecteur uniteur de la direction du boid
                Vector2D W = (Math.atan2(U.y, U.x) - Math.atan2(V.y, V.x) < 0) ? new Vector2D(-U.y, U.x) : new Vector2D(U.y, -U.x); // Vecteur unitaire perpendiculaire à U
                Vector2D force = Vector2D.add(U.multiply(BO.norm() - m), W.multiply(q));

                if (BO.norm() <= obstacle.avoidanceRadius) {
                    forces = force;
                } else {
                    forces.add(force.multiply(0.5));
                }
            }
        }
    }

    private boolean willCollideObstacle(Obstacle obstacle) {
        Vector2D BO = Vector2D.subtract(obstacle.position, this.location); // Vecteur entre le boid et le centre de l'obstacle
        double projection = Vector2D.scalarProduct(BO, velocity.normalized()); // Projection de BO sur la direction du boid
        double distanceBoidObstacle = Math.sqrt(Math.pow(BO.norm(), 2) - Math.pow(projection, 2)); // Plus petite distance de l'obstacle que le boid pourra atteindre

        return (BO.norm() <= REPULSE_RANGE + obstacle.avoidanceRadius && distanceBoidObstacle < obstacle.avoidanceRadius && projection >= 0);
    }

    private void computeWallAvoidanceForce() {
        double forceIntensity = 3;
        if (location.x - REPULSE_RANGE <= 150) {
            forces.add(new Vector2D(forceIntensity, 0));
        } else if (location.x + REPULSE_RANGE >= AppView.SIMULATION_PANEL_WIDTH - 150) {
            forces.add(new Vector2D(-forceIntensity, 0));
        }

        if (location.y - REPULSE_RANGE <= 150) {
            forces.add(new Vector2D(0, forceIntensity));
        } else if (location.y + REPULSE_RANGE >= AppView.HEIGHT - 150) {
            forces.add(new Vector2D(0, -forceIntensity));
        }
    }

    private void applyForces() {
        if (trails.size() > MAX_TRAILS) {
            trails.remove(0);
        }
        trails.add(new Vector2D(location));

        velocity.add(forces);
        if (velocity.norm() > flock.getSpeedLimit()) {
            velocity.scaleNorm(flock.getSpeedLimit());
        } else if (velocity.norm() < App.BOIDS_MIN_SPEED) {
            velocity.scaleNorm(App.BOIDS_MIN_SPEED);
        }

        location.add(velocity);
    }

    public void draw(Graphics2D g) {
        computeForces();

        drawBoid(g);
        if (flock.displayViewRange) drawViewRange(g);
        if (flock.displayTrails) drawTrails(g);
    }

    private void drawBoid(Graphics2D g) {
        double boidAngle = Math.PI / 4;

        Path2D boidShape = new Path2D.Double();

        boidShape.moveTo(0, 0);
        boidShape.lineTo(-BOID_SIZE * Math.cos(boidAngle), BOID_SIZE * Math.sin(boidAngle));
        boidShape.lineTo(BOID_SIZE, 0);
        boidShape.lineTo(-BOID_SIZE * Math.cos(boidAngle), -BOID_SIZE * Math.sin(boidAngle));
        boidShape.closePath();

        AffineTransform initState = g.getTransform();

        g.translate(location.x, location.y);

        double directionAngle = Math.atan2(velocity.y, velocity.x);
        g.rotate(directionAngle);

        g.setColor(flock.getColors().getColor());
        g.fill(boidShape);

        g.setTransform(initState);
    }

    private void drawViewRange(Graphics2D g) {
        double viewRange = flock.getViewRange();

        Arc2D viewRangeShape = new Arc2D.Double(-viewRange, -viewRange, 2 * viewRange, 2 * viewRange, 180 + DEAD_ANGLE / 2, 360 - DEAD_ANGLE, Arc2D.PIE);

        AffineTransform initState = g.getTransform();

        g.translate(location.x, location.y);

        double directionAngle = Math.atan2(velocity.y, velocity.x);
        g.rotate(directionAngle);
        g.setColor(Color.RED);
        g.draw(viewRangeShape);

        g.setTransform(initState);
    }

    public void drawTrails(Graphics2D g) {
        for (int i = 0; i < trails.size(); i++) {
            Vector2D currentTrail = trails.get(i);
            Vector2D nextTrack = (i < trails.size() - 1) ? trails.get(i + 1) : location;
            int alpha = i * 255 / trails.size();
            Color colorTrail = flock.getColors().getColor().darker();
            g.setColor(new Color(colorTrail.getRed(), colorTrail.getGreen(), colorTrail.getBlue(), alpha));
            g.draw(new Line2D.Double(currentTrail.x, currentTrail.y, nextTrack.x, nextTrack.y));
        }
    }

    public void clearTrails() {
        trails.clear();
    }

    public String toString() {
        return "Boid en " + location;
    }
}
