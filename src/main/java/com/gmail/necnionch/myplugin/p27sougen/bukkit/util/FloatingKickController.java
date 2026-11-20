package com.gmail.necnionch.myplugin.p27sougen.bukkit.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class FloatingKickController {

    private static FloatingKickController INSTANCE;

    private final Method mCraftPlayerGetServerPlayer;  // CraftPlayer#getHandle -> ServerPlayer
    private final Field fServerPlayerConnection;  // ServerPlayer.connection (ServerGamePacketListenerImpl)
    private final Field fSGPLClientIsFloating;  // ServerGamePacketListenerImpl.clientIsFloating
    private final Field fSGPLAboveGroundTickCount;  // ServerGamePacketListenerImpl.aboveGroundTickCount

    private static FloatingKickController create() throws ReflectiveOperationException {
        Class<?> cCraftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
        if (!Player.class.isAssignableFrom(cCraftPlayer)) {
            throw new IllegalStateException("CraftPlayer does not implement Player");
        }

        Method mCraftPlayerGetServerPlayer = cCraftPlayer.getMethod("getHandle");
        Class<?> cServerPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
        Field fServerPlayerConnection = cServerPlayer.getField("connection");
        Class<?> cServerGamePacketListenerImpl = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
        Field fSGPLClientIsFloating = cServerGamePacketListenerImpl.getDeclaredField("clientIsFloating");
        fSGPLClientIsFloating.setAccessible(true);
        if (fSGPLClientIsFloating.getType() != boolean.class) {
            throw new NoSuchFieldException("clientIsFloating is not boolean");
        }
        Field fSGPLAboveGroundTickCount = cServerGamePacketListenerImpl.getDeclaredField("aboveGroundTickCount");
        fSGPLAboveGroundTickCount.setAccessible(true);
        if (fSGPLAboveGroundTickCount.getType() != int.class) {
            throw new NoSuchFieldException("aboveGroundTickCount is not int");
        }

        return new FloatingKickController(mCraftPlayerGetServerPlayer, fServerPlayerConnection, fSGPLClientIsFloating, fSGPLAboveGroundTickCount);
    }

    public static FloatingKickController getOrCreate() throws ReflectiveOperationException {
        if (INSTANCE == null) {
            INSTANCE = create();
        }
        return INSTANCE;
    }

    private FloatingKickController(Method mCraftPlayerGetServerPlayer, Field fServerPlayerConnection, Field fSGPLClientIsFloating, Field fSGPLAboveGroundTickCount) {
        this.mCraftPlayerGetServerPlayer = mCraftPlayerGetServerPlayer;
        this.fServerPlayerConnection = fServerPlayerConnection;
        this.fSGPLClientIsFloating = fSGPLClientIsFloating;
        this.fSGPLAboveGroundTickCount = fSGPLAboveGroundTickCount;
    }

    public boolean resetFlyingTicks(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("resetFlyingTicks must be called on the main thread");
        }

        try {
            Object serverPlayer = mCraftPlayerGetServerPlayer.invoke(player);
            Object connection = fServerPlayerConnection.get(serverPlayer);
            fSGPLClientIsFloating.setBoolean(connection, false);
            fSGPLAboveGroundTickCount.setInt(connection, 0);
        } catch (ReflectiveOperationException e) {
            return false;
        }
        return true;
    }

}
