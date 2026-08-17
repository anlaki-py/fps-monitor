package com.anlaki.fpsmonitor;

interface IShellService {
    String run(String operation) = 1;
    void destroy() = 16777114;
}
