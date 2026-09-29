package com.ninfinity.gmsdoze;

interface IUserService {
    // Transaction ID bắt buộc của Shizuku
    void destroy() = 16777114;

    String exec(String command) = 1;
}
