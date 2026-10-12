# ColorOS LiquidGlass

为 ColorOS 添加液态玻璃效果

对源项目做出的修改写在这里

ai写得太快了，但是我尽量的去看了，看不完还用其他agent继续看

大概明显的修改如下

首先就是把 文件夹 和 消息得背景固定为壁纸了，这可能导致动态壁纸出现违和的情况，但是我还是没有考虑写动态帧率或者可调帧率对于这里的方案，因为我觉得玻璃块本身可以根据角度变化背景的各个反射和色散本身就够了

所以滑动的时候并不会感觉到卡顿（纯组合（Composition）：不替换View背景，拦截宿主 onDraw(Canvas)，自己持有GlassDrawable并调用 glass.draw(canvas)

共享壁纸（Shared GPU Wallpaper）：标记了 markSharedWallpaper，全局 由 GPU RenderEffect 生成单张模糊纹理，卡片移动只改采样UV 矩阵，零重复截屏，几乎没有开销 ~~这是ai讲的我也看不懂，项目也不熟悉~~

给背景极其可能连续产生变化的组件加入了可调帧率

现在在液态玻璃重叠的情况下还是略有卡顿

还有一些小的修改

就是比如说滑块设计得有问题，就是滑条不应该在被触碰得时候更改键值，而应该在滑块触碰得时候才能更改键值否则是很容易误触得


使用 LSPosed API 101

目前适用于 ColorOS 16 (仅在金标系统上测试) 和 ColorOS 14，其它版本未测试 (实现液态玻璃效果至少需要 Android 13)

ColorOS 16:<br>
<img width="228" height="495" alt="Screenshot_01" src="imgs/Screenshot_01.jpg" /> <img width="228" height="495" alt="Screenshot_02" src="imgs/Screenshot_02.jpg" /> <img width="228" height="495" alt="Screenshot_03" src="imgs/Screenshot_03.png" />

ColorOS 14:<br>
<img width="233" height="520" alt="Screenshot_04" src="imgs/Screenshot_04.jpg" /> <img width="233" height="520" alt="Screenshot_05" src="imgs/Screenshot_05.jpg" /> <img width="233" height="520" alt="Screenshot_06" src="imgs/Screenshot_06.jpg" />