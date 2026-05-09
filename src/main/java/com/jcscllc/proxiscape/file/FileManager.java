package com.jcscllc.proxiscape.file;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import java.io.*;
import java.util.Scanner;

public class FileManager {

    public static void writeInfo(String fileName, Mixer.Info info) throws IOException {
        File file = new File(fileName);

        file.createNewFile();

        FileWriter writer = new FileWriter(file, false);

        writer.write(info.getName() + "\n");
        writer.write(info.getDescription() + "\n");
        writer.write(info.getVendor() + "\n");
        writer.write(info.getVersion() + "\n");

        writer.close();
    }

    public static Mixer.Info readInfo(String fileName, boolean isMic) throws FileNotFoundException {
        File file = new File(fileName);

        if (!file.exists())
            return null;

        Scanner scanner = new Scanner(file);

        String name = scanner.nextLine();
        String description = scanner.nextLine();
        String vendor = scanner.nextLine();
        String version = scanner.nextLine();

        scanner.close();

        Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);

            for (Line.Info lineInfo : isMic ? mixer.getTargetLineInfo() : mixer.getSourceLineInfo()) {
                if (lineInfo instanceof DataLine.Info)
                    if (mixerInfo.getName().equals(name) && mixerInfo.getDescription().equals(description) &&
                            mixerInfo.getVendor().equals(vendor) && mixerInfo.getVersion().equals(version))
                        return mixerInfo;

            }
        }

        return null;
    }
}
