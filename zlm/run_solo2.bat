@echo off
rem 双击启动第二实例（模拟集群）：config-solo2.ini + HTTP 8089
rem 参数化入口在 run_solo.bat，这里只是固定参数的快捷方式
rem 前置条件：同目录存在 config-solo2.ini（端口与 mediaServerId 已与第一实例错开）
call "%~dp0run_solo.bat" config-solo2.ini 8089
