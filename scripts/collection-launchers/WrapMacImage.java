import java.nio.file.Files;
import java.nio.file.Path;
import com.acme.assetmanagement.integration.deployment.MacDiskImage;

class WrapMacImage {
    public static void main(String[] args) throws Exception {
        Files.write(Path.of(args[1]), MacDiskImage.wrap(Files.readAllBytes(Path.of(args[0]))));
    }
}
