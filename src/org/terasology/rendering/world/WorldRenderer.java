/*
 * Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.world;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.terasology.components.AABBCollisionComponent;
import org.terasology.components.PlayerComponent;
import org.terasology.entitySystem.EntityManager;
import org.terasology.entitySystem.componentSystem.RenderSystem;
import org.terasology.game.ComponentSystemManager;
import org.terasology.game.CoreRegistry;
import org.terasology.game.Terasology;
import org.terasology.logic.entities.Entity;
import org.terasology.logic.generators.ChunkGeneratorTerrain;
import org.terasology.logic.global.LocalPlayer;
import org.terasology.logic.manager.*;
import org.terasology.logic.systems.BlockDamageRenderer;
import org.terasology.logic.systems.BlockParticleEmitterSystem;
import org.terasology.logic.systems.LocalPlayerSystem;
import org.terasology.logic.systems.MeshRenderer;
import org.terasology.logic.world.*;
import org.terasology.math.TeraMath;
import org.terasology.model.blocks.Block;
import org.terasology.model.blocks.management.BlockManager;
import org.terasology.model.structures.AABB;
import org.terasology.model.structures.BlockPosition;
import org.terasology.performanceMonitor.PerformanceMonitor;
import org.terasology.rendering.cameras.Camera;
import org.terasology.rendering.cameras.DefaultCamera;
import org.terasology.rendering.interfaces.IGameObject;
import org.terasology.rendering.particles.BlockParticleEmitter;
import org.terasology.rendering.physics.BulletPhysicsRenderer;
import org.terasology.rendering.primitives.ChunkMesh;

import javax.imageio.ImageIO;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL11.*;

/**
 * The world of Terasology. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 * <p/>
 * The world is randomly generated by using a bunch of Perlin noise generators initialized
 * with a favored seed value.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class WorldRenderer implements IGameObject {
    /* WORLD PROVIDER */
    private final IWorldProvider _worldProvider;
    private EntityManager _entityManager;
    private LocalPlayerSystem _localPlayerSystem;

    /* PLAYER */
    private LocalPlayer _player;

    /* CAMERA */
    public enum CAMERA_MODE {
        PLAYER,
        SPAWN
    }

    private CAMERA_MODE _cameraMode = CAMERA_MODE.PLAYER;
    private Camera _spawnCamera = new DefaultCamera();
    private DefaultCamera _defaultCamera = new DefaultCamera();
    private Camera _activeCamera = _defaultCamera;

    /* CHUNKS */
    private final ArrayList<Chunk> _chunksInProximity = new ArrayList<Chunk>();
    private int _chunkPosX, _chunkPosZ;

    /* RENDERING */
    private final LinkedList<Chunk> _renderQueueChunksOpaque = new LinkedList<Chunk>();
    private final PriorityQueue<Chunk> _renderQueueChunksSortedWater = new PriorityQueue<Chunk>();
    private final PriorityQueue<Chunk> _renderQueueChunksSortedBillboards = new PriorityQueue<Chunk>();
    private final LinkedList<IGameObject> _renderQueueOpaque = new LinkedList<IGameObject>();
    private final LinkedList<IGameObject> _renderQueueTransparent = new LinkedList<IGameObject>();

    /* CORE GAME OBJECTS */
    private final PortalManager _portalManager;

    /* PARTICLE EMITTERS */
    private final BlockParticleEmitter _blockParticleEmitter = new BlockParticleEmitter(this);

    /* HORIZON */
    private final Skysphere _skysphere;

    /* TICKING */
    private double _tick = 0;
    private int _tickTock = 0;
    private long _lastTick;

    /* UPDATING */
    private final ChunkUpdateManager _chunkUpdateManager;

    /* EVENTS */
    private final WorldTimeEventManager _worldTimeEventManager;

    /* PHYSICS */
    private final BulletPhysicsRenderer _bulletRenderer;

    /* BLOCK GRID */
    private final BlockGrid _blockGrid;

    /* STATISTICS */
    private int _statDirtyChunks = 0, _statVisibleChunks = 0, _statIgnoredPhases = 0;

    /* OTHER SETTINGS */
    private boolean _wireframe;

    private ComponentSystemManager _systemManager;

    /**
     * Initializes a new (local) world for the single player mode.
     *
     * @param title The title/description of the world
     * @param seed  The seed string used to generate the terrain
     */
    public WorldRenderer(String title, String seed, EntityManager manager, LocalPlayerSystem localPlayerSystem) {
        _worldProvider = new LocalWorldProvider(title, seed);
        _skysphere = new Skysphere(this);
        _chunkUpdateManager = new ChunkUpdateManager();
        _worldTimeEventManager = new WorldTimeEventManager(_worldProvider);
        _portalManager = new PortalManager(manager);
        _blockGrid = new BlockGrid();
        _bulletRenderer = new BulletPhysicsRenderer(this);
        _entityManager = manager;
        _localPlayerSystem = localPlayerSystem;
        _localPlayerSystem.setPlayerCamera(_defaultCamera);
        _systemManager = CoreRegistry.get(ComponentSystemManager.class);

        initTimeEvents();
    }

    /**
     * Updates the list of chunks around the player.
     *
     * @param force Forces the update
     * @return True if the list was changed
     */
    public boolean updateChunksInProximity(boolean force) {
        int newChunkPosX = calcCamChunkOffsetX();
        int newChunkPosZ = calcCamChunkOffsetZ();

        int viewingDistance = Config.getInstance().getActiveViewingDistance();

        if (_chunkPosX != newChunkPosX || _chunkPosZ != newChunkPosZ || force) {

            _chunksInProximity.clear();

            for (int x = -(viewingDistance / 2); x < (viewingDistance / 2); x++) {
                for (int z = -(viewingDistance / 2); z < (viewingDistance / 2); z++) {
                    Chunk c = _worldProvider.getChunkProvider().getChunk(calcCamChunkOffsetX() + x, 0, calcCamChunkOffsetZ() + z);
                    _chunksInProximity.add(c);
                }
            }

            _chunkPosX = newChunkPosX;
            _chunkPosZ = newChunkPosZ;

            Collections.sort(_chunksInProximity);
            return true;
        }

        return false;
    }

    /*public boolean isInRange(Vector3f pos) {
        Vector3f dist = new Vector3f();
        dist.sub(getPlayerPosition(), pos);

        double distLength = dist.length();

        return distLength < (Config.getInstance().getActiveViewingDistance() * 8);
    }*/
    
    private Vector3f getPlayerPosition() {
        if (_player != null) {
            return _player.getPosition();
        }
        return new Vector3f();
    }

    /**
     * Creates the world time events to play the game's soundtrack at specific times.
     */
    public void initTimeEvents() {
        // SUNRISE
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.1, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Sunrise");
            }
        });

        // AFTERNOON
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.25, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Afternoon");
            }
        });

        // SUNSET
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.4, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Sunset");
            }
        });

        // NIGHT
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.6, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Dimlight");
            }
        });

        // NIGHT
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.75, true) {
            @Override
            public void run() {
                AudioManager.playMusic("OtherSide");
            }
        });

        // BEFORE SUNRISE
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.9, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Resurface");
            }
        });
    }

    /**
     * Updates the currently visible chunks (in sight of the player).
     */
    public void updateAndQueueVisibleChunks() {
        _statDirtyChunks = 0;
        _statVisibleChunks = 0;
        _statIgnoredPhases = 0;

        for (int i = 0; i < _chunksInProximity.size(); i++) {
            Chunk c = _chunksInProximity.get(i);

            if (isChunkVisible(c)) {
                if (c.triangleCount(ChunkMesh.RENDER_PHASE.OPAQUE) > 0)
                    _renderQueueChunksOpaque.add(c);
                else
                    _statIgnoredPhases++;

                if (c.triangleCount(ChunkMesh.RENDER_PHASE.WATER_AND_ICE) > 0)
                    _renderQueueChunksSortedWater.add(c);
                else
                    _statIgnoredPhases++;

                if (c.triangleCount(ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT) > 0)
                    _renderQueueChunksSortedBillboards.add(c);
                else
                    _statIgnoredPhases++;

                c.update();

                if (c.isDirty() || c.isLightDirty() || c.isFresh()) {
                    _statDirtyChunks++;
                    _chunkUpdateManager.queueChunkUpdate(c, ChunkUpdateManager.UPDATE_TYPE.DEFAULT);
                }

                _statVisibleChunks++;
            } else if (i > Config.getInstance().getMaxChunkVBOs()) {
                // Make sure not too many chunk VBOs are available in the video memory at the same time
                // Otherwise VBOs are moved into system memory which is REALLY slow and causes lag
                c.clearMeshes();
            }
        }
    }

    private void queueRenderer() {
        PerformanceMonitor.startActivity("Update and Queue Chunks");
        updateAndQueueVisibleChunks();
        PerformanceMonitor.endActivity();

        _renderQueueTransparent.add(_bulletRenderer);
        _renderQueueTransparent.add(_blockParticleEmitter);
        _renderQueueTransparent.add(_blockGrid);

        Chunk.resetStats();
    }

    /**
     * Renders the world.
     */
    public void render() {
        /* QUEUE RENDERER */
        queueRenderer();

        PostProcessingRenderer.getInstance().beginRenderScene();

        /* SKYSPHERE */
        PerformanceMonitor.startActivity("Render Sky");
        getActiveCamera().lookThroughNormalized();
        _skysphere.render();
        PerformanceMonitor.endActivity();

        /* WORLD RENDERING */
        PerformanceMonitor.startActivity("Render World");
        getActiveCamera().lookThrough();
        if (Config.getInstance().isDebugCollision()) {
            renderDebugCollision();
        };

        glEnable(GL_LIGHT0);

        boolean headUnderWater = false;

        headUnderWater = (_cameraMode == CAMERA_MODE.PLAYER && isUnderwater(getActiveCamera().getPosition()));

        if (_wireframe)
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);

        PerformanceMonitor.startActivity("RenderOpaque");

        while (_renderQueueOpaque.size() > 0)
            _renderQueueOpaque.poll().render();
        for (RenderSystem renderer : _systemManager.iterateRenderSubscribers()) {
            renderer.renderOpaque();
        }


        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render ChunkOpaque");

        /*
         * FIRST RENDER PASS: OPAQUE ELEMENTS
         */
        while (_renderQueueChunksOpaque.size() > 0)
            _renderQueueChunksOpaque.poll().render(ChunkMesh.RENDER_PHASE.OPAQUE);

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render ChunkTransparent");

        /*
         * SECOND RENDER PASS: BILLBOARDS
         */
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        while (_renderQueueChunksSortedBillboards.size() > 0)
            _renderQueueChunksSortedBillboards.poll().render(ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT);

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render Transparent");

        while (_renderQueueTransparent.size() > 0)
            _renderQueueTransparent.poll().render();
        for (RenderSystem renderer : _systemManager.iterateRenderSubscribers()) {
            renderer.renderTransparent();
        }

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render ChunkWaterIce");

        // Make sure the water surface is rendered if the player is swimming
        if (headUnderWater) {
            glDisable(GL11.GL_CULL_FACE);
        }

        /*
        * THIRD (AND FOURTH) RENDER PASS: WATER AND ICE
        */
        while (_renderQueueChunksSortedWater.size() > 0) {
            Chunk c = _renderQueueChunksSortedWater.poll();

            for (int j = 0; j < 2; j++) {

                if (j == 0) {
                    glColorMask(false, false, false, false);
                    c.render(ChunkMesh.RENDER_PHASE.WATER_AND_ICE);
                } else {
                    glColorMask(true, true, true, true);
                    c.render(ChunkMesh.RENDER_PHASE.WATER_AND_ICE);
                }
            }
        }

        for (RenderSystem renderer : _systemManager.iterateRenderSubscribers()) {
            renderer.renderOverlay();
        }

        glDisable(GL_BLEND);

        if (headUnderWater)
            glEnable(GL11.GL_CULL_FACE);

        if (_wireframe)
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        glDisable(GL_LIGHT0);

        PerformanceMonitor.endActivity();

        PostProcessingRenderer.getInstance().endRenderScene();

        /* RENDER THE FINAL POST-PROCESSED SCENE */
        PerformanceMonitor.startActivity("Render Post-Processing");
        PostProcessingRenderer.getInstance().renderScene();
        PerformanceMonitor.endActivity();

        /* FIRST PERSON VIEW ELEMENTS */
        if (_cameraMode == CAMERA_MODE.PLAYER)
            _localPlayerSystem.renderFirstPersonViewElements();
    }

    public float getRenderingLightValue() {
        return getRenderingLightValueAt(getActiveCamera().getPosition());
    }

    public float getRenderingLightValueAt(Vector3d pos) {
        double lightValueSun = ((double) _worldProvider.getLightAtPosition(pos, Chunk.LIGHT_TYPE.SUN));
        lightValueSun = lightValueSun / 15.0;
        lightValueSun *= getDaylight();
        double lightValueBlock = _worldProvider.getLightAtPosition(pos, Chunk.LIGHT_TYPE.BLOCK);
        lightValueBlock = lightValueBlock / 15.0;

        return (float) TeraMath.clamp(lightValueSun + lightValueBlock * (1.0 - lightValueSun));
    }

    public void update(double delta) {
        PerformanceMonitor.startActivity("Cameras");
        animateSpawnCamera(delta);
        _spawnCamera.update(delta);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Update Tick");
        updateTick(delta);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Update Close Chunks");
        updateChunksInProximity(false);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Skysphere");
        _skysphere.update(delta);
        PerformanceMonitor.endActivity();

        if (_activeCamera != null) {
            _activeCamera.update(delta);
        }

        // Update the particle emitters
        PerformanceMonitor.startActivity("Block Particle Emitter");
        _blockParticleEmitter.update(delta);
        PerformanceMonitor.endActivity();

        // Free unused space
        PerformanceMonitor.startActivity("Flush World Cache");
        _worldProvider.getChunkProvider().flushCache();
        PerformanceMonitor.endActivity();

        // And finally fire any active events
        PerformanceMonitor.startActivity("Fire Events");
        _worldTimeEventManager.fireWorldTimeEvents();
        PerformanceMonitor.endActivity();

        // Simulate world
        PerformanceMonitor.startActivity("Liquid");
        _worldProvider.getLiquidSimulator().simulate(false);
        PerformanceMonitor.endActivity();
        PerformanceMonitor.startActivity("Growth");
        _worldProvider.getGrowthSimulator().simulate(false);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Physics Renderer");
        _bulletRenderer.update(delta);
        PerformanceMonitor.endActivity();
    }

    private void renderDebugCollision() {
        if (_player != null && _player.isValid()) {
            AABBCollisionComponent collision = _player.getEntity().getComponent(AABBCollisionComponent.class);
            if (collision != null) {
                Vector3f worldLoc = _player.getPosition();
                AABB aabb = new AABB(new Vector3d(worldLoc), new Vector3d(collision.getExtents()));
                aabb.render(1f);
            }
        }

        List<BlockPosition> blocks = WorldUtil.gatherAdjacentBlockPositions(new Vector3f(getActiveCamera().getPosition()));

        for (int i = 0; i < blocks.size(); i++) {
            BlockPosition p = blocks.get(i);
            byte blockType = getWorldProvider().getBlockAtPosition(new Vector3d(p.x, p.y, p.z));
            Block block = BlockManager.getInstance().getBlock(blockType);
            for (AABB blockAABB : block.getColliders(p.x, p.y, p.z)) {
                blockAABB.render(1f);
            }
        }
    }
    
    private boolean isUnderwater(Vector3d pos) {

        BlockPosition p = new BlockPosition(pos);
        byte blockType = getWorldProvider().getBlockAtPosition(pos);
        Block block = BlockManager.getInstance().getBlock(blockType);
        if (block.isLiquid()) {
            for (AABB blockAABB : block.getColliders(p.x, p.y, p.z)) {
                if (blockAABB.contains(pos)) {
                    return true;
                }
            }
        }
        return false;
    }


    private void animateSpawnCamera(double delta) {
        if (_player == null || !_player.isValid())
            return;
        PlayerComponent player = _player.getEntity().getComponent(PlayerComponent.class);
        Vector3f cameraPosition = new Vector3f(player.spawnPosition);
        cameraPosition.y += 32;
        cameraPosition.x += Math.sin(getTick() * 0.0005f) * 32f;
        cameraPosition.z += Math.cos(getTick() * 0.0005f) * 32f;

        Vector3f playerToCamera = new Vector3f();
        playerToCamera.sub(getPlayerPosition(), cameraPosition);
        double distanceToPlayer = playerToCamera.length();

        Vector3f cameraDirection = new Vector3f();

        if (distanceToPlayer > 64.0) {
            cameraDirection.sub(player.spawnPosition, cameraPosition);
        } else {
            cameraDirection.set(playerToCamera);
        }

        cameraDirection.normalize();

        _spawnCamera.getPosition().set(cameraPosition);
        _spawnCamera.getViewingDirection().set(cameraDirection);
    }

    /**
     * Performs and maintains tick-based logic. If the game is paused this logic is not executed
     * First effect: update the _tick variable that animation is based on
     * Secondary effect: Trigger spawning (via PortalManager) once every second
     * Tertiary effect: Trigger socializing (via MobManager) once every 10 seconds
     */
    private void updateTick(double delta) {
        // Update the animation tick
        _tick += delta;

        // This block is based on seconds or less frequent timings
        if (Terasology.getInstance().getTimeInMs() - _lastTick >= 1000) {
            _tickTock++;
            _lastTick = Terasology.getInstance().getTimeInMs();

            // PortalManager ticks for spawning once a second
            _portalManager.tickSpawn();
        }
    }

    /**
     * Returns the maximum height at a given position.
     *
     * @param x The X-coordinate
     * @param z The Z-coordinate
     * @return The maximum height
     */
    public final int maxHeightAt(int x, int z) {
        for (int y = Chunk.CHUNK_DIMENSION_Y - 1; y >= 0; y--) {
            if (_worldProvider.getBlock(x, y, z) != 0x0)
                return y;
        }

        return 0;
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the x-axis
     */
    private int calcCamChunkOffsetX() {
        return (int) (getActiveCamera().getPosition().x / Chunk.CHUNK_DIMENSION_X);
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the z-axis
     */
    private int calcCamChunkOffsetZ() {
        return (int) (getActiveCamera().getPosition().z / Chunk.CHUNK_DIMENSION_Z);
    }

    /**
     * Sets a new player and spawns him at the spawning point.
     *
     * @param p The player
     */
    public void setPlayer(LocalPlayer p) {
        /*if (_player != null) {
            _player.unregisterObserver(_chunkUpdateManager);
            _player.unregisterObserver(_worldProvider.getGrowthSimulator());
            _player.unregisterObserver(_worldProvider.getLiquidSimulator());
        } */

        _player = p;
        /*_player.registerObserver(_chunkUpdateManager);
        _player.registerObserver(_worldProvider.getGrowthSimulator());
        _player.registerObserver(_worldProvider.getLiquidSimulator());

        _player.load();
        _player.setSpawningPoint(_worldProvider.nextSpawningPoint());*/
        updateChunksInProximity(true);

        /*_player.reset();

        // Only respawn the player if no position was loaded
        if (_player.getPosition().equals(new Vector3d(0.0, 0.0, 0.0))) {
            _player.respawn();
        } */
    }

    /**
     * Creates the first Portal if it doesn't exist yet
     */
    public void initPortal() {
        if (!_portalManager.hasPortal()) {
            Vector3d loc = new Vector3d(getPlayerPosition().x, getPlayerPosition().y + 4, getPlayerPosition().z);
            Terasology.getInstance().getLogger().log(Level.INFO, "Portal location is" + loc);
            _worldProvider.setBlock((int) loc.x - 1, (int) loc.y, (int) loc.z, BlockManager.getInstance().getBlock("PortalBlock").getId(), false, true);
            _portalManager.addPortal(loc);
        }
    }

    /**
     * Disposes this world.
     */
    public void dispose() {
        _worldProvider.dispose();
        AudioManager.getInstance().stopAllSounds();
    }

    /**
     * Returns true if no more chunks can be generated.
     *
     * @return
     */
    public boolean generateChunk() {
        for (int i = 0; i < _chunksInProximity.size(); i++) {
            Chunk c = _chunksInProximity.get(i);

            if (c.isDirty() || c.isLightDirty() || c.isFresh()) {
                c.processChunk();
                c.generateVBOs();
                return false;
            }
        }

        return true;
    }

    public void printScreen() {
        GL11.glReadBuffer(GL11.GL_FRONT);
        final int width = Display.getDisplayMode().getWidth();
        final int height = Display.getDisplayMode().getHeight();
        //int bpp = Display.getDisplayMode().getBitsPerPixel(); does return 0 - why?
        final int bpp = 4;
        final ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * bpp); // hardcoded until i know how to get bpp
        GL11.glReadPixels(0, 0, width, height, (bpp == 3) ? GL11.GL_RGB : GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        Runnable r = new Runnable() {
            public void run() {
                Calendar cal = Calendar.getInstance();
                SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmssSSS");

                File file = new File(sdf.format(cal.getTime()) + ".png");
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

                for (int x = 0; x < width; x++)
                    for (int y = 0; y < height; y++) {
                        int i = (x + (width * y)) * bpp;
                        int r = buffer.get(i) & 0xFF;
                        int g = buffer.get(i + 1) & 0xFF;
                        int b = buffer.get(i + 2) & 0xFF;
                        image.setRGB(x, height - (y + 1), (0xFF << 24) | (r << 16) | (g << 8) | b);
                    }

                try {
                    ImageIO.write(image, "png", file);
                } catch (IOException e) {
                    Terasology.getInstance().getLogger().log(Level.WARNING, "Could not save image!", e);
                }
            }
        };

        Terasology.getInstance().submitTask("Write screenshot", r);
    }


    @Override
    public String toString() {
        return String.format("world (biome: %s, time: %.2f, exposure: %.2f, sun: %.2f, cache: %fMb, dirty: %d, ign: %d, vis: %d, tri: %d, empty: %d, !ready: %d, seed: \"%s\", title: \"%s\")", getPlayerBiome(), _worldProvider.getTime(), PostProcessingRenderer.getInstance().getExposure(), _skysphere.getSunPosAngle(), _worldProvider.getChunkProvider().size(), _statDirtyChunks, _statIgnoredPhases, _statVisibleChunks, Chunk._statRenderedTriangles, Chunk._statChunkMeshEmpty, Chunk._statChunkNotReady, _worldProvider.getSeed(), _worldProvider.getTitle());
    }

    public LocalPlayer getPlayer() {
        return _player;
    }

    public boolean isAABBVisible(AABB aabb) {
        return getActiveCamera().getViewFrustum().intersects(aabb);
    }

    public boolean isChunkVisible(Chunk c) {
        return getActiveCamera().getViewFrustum().intersects(c.getAABB());
    }

    public boolean isEntityVisible(Entity e) {
        return getActiveCamera().getViewFrustum().intersects(e.getAABB());
    }

    public double getDaylight() {
        return _skysphere.getDaylight();
    }

    public BlockParticleEmitter getBlockParticleEmitter() {
        return _blockParticleEmitter;
    }

    public ChunkGeneratorTerrain.BIOME_TYPE getPlayerBiome() {
        Vector3f pos = getPlayerPosition();
        return _worldProvider.getActiveBiome((int) pos.x, (int) pos.z);
    }

    public double getActiveHumidity(Vector3d pos) {
        return _worldProvider.getHumidityAt((int) pos.x, (int) pos.z);
    }

    public double getActiveTemperature(Vector3d pos) {
        return _worldProvider.getTemperatureAt((int) pos.x, (int) pos.z);
    }

    public IWorldProvider getWorldProvider() {
        return _worldProvider;
    }

    public BlockGrid getBlockGrid() {
        return _blockGrid;
    }

    public Skysphere getSkysphere() {
        return _skysphere;
    }

    public double getTick() {
        return _tick;
    }

    public ArrayList<Chunk> getChunksInProximity() {
        return _chunksInProximity;
    }

    public boolean isWireframe() {
        return _wireframe;
    }

    public void setWireframe(boolean _wireframe) {
        this._wireframe = _wireframe;
    }

    public BulletPhysicsRenderer getBulletRenderer() {
        return _bulletRenderer;
    }

    public Camera getActiveCamera() {
        return _activeCamera;
    }

    //TODO: Review
    public void setCameraMode(CAMERA_MODE mode) {
        _cameraMode = mode;
        switch (mode) {
            case PLAYER:
                _activeCamera = _defaultCamera;
                break;
            default:
                _activeCamera = _spawnCamera;
                break;
        }
    }
}
