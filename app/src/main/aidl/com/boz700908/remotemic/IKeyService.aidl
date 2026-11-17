// 以 shell（Shizuku）身份运行的按键注入接口。
// 所有 keyCode 都是 android.view.KeyEvent 的 KEYCODE_* 常量值，
// metaState 是 KeyEvent.META_*_ON 位组合。
package com.boz700908.remotemic;

interface IKeyService {

    /** 一次完整点按：DOWN 后 UP。 */
    void press(int keyCode, int metaState);

    /** 只按下不抬起，用于「按住某键」。 */
    void down(int keyCode, int metaState);

    /** 抬起。 */
    void up(int keyCode, int metaState);

    /** 按住 ms 毫秒（期间持续注入 repeat），再抬起。 */
    void longPress(int keyCode, int metaState, int ms);

    /** 组合键：先按住修饰键，再按主键，保持 holdMs 后逆序抬起。 */
    void combination(in int[] keyCodes, int holdMs);

    /** 释放所有仍按下状态的键（异常兜底，避免键卡住）。 */
    void releaseAll();

    /** 结束服务。 */
    void destroy();
}
