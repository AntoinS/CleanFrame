package com.cleanframe.addonapi;

/** Version 1: bounded JSON commands, authenticated by package and signing identity. */
interface IRemoteAddon {
    String request(String json);
}
