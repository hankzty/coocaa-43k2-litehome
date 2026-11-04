# 离线镜像文件工具

tools/OfflineImage.ps1 在电脑上处理两个独立文件。Build 根据补丁规格生成新文件，Undo 以已打补丁的文件为输入，生成 SHA256 与规格中的原始文件相符的新文件。**Undo 仅撤销本地文件中的字节修改，不能回滚已经刷入电视的系统，也不能救砖。**

维护者须自备合法取得的镜像和与之精确匹配的补丁规格。工具对输入长度、完整 SHA256、区间顺序和重叠、区间原始字节以及输出 SHA256 逐项校验。输入和输出必须不同，输出路径必须尚不存在。

补丁规格为 JSON。format 为 1，device 为 sky638_5s21，bytes 为文件长度，originalSha256 和 patchedSha256 为完整文件的校验值。patches 每项包含 offset、before 和 after，后两项为等长的 Base64 字节串。

从项目根目录运行以下命令，把示例路径换成自己的文件路径。

    .\tools\OfflineImage.ps1 -Mode Build -InputImage .\input.img -OutputImage .\patched.img -PatchFile .\local-patch.json
    .\tools\OfflineImage.ps1 -Mode Undo -InputImage .\patched.img -OutputImage .\restored.img -PatchFile .\local-patch.json

校验输出与本地规格相符，只能确认这次文件转换。若要验证启动或恢复能力，需要另行验证设备专用的部署和维修流程。
