package mchorse.bbs_mod.utils.keyframes;

import java.util.Map;
import java.lang.reflect.Proxy;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.utils.Anchor;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.joml.Matrices;
import mchorse.bbs_mod.utils.keyframes.factories.AnchorKeyframeFactory;
import mchorse.bbs_mod.utils.pose.Transform;
import org.joml.Matrix4f;

/** Standalone checks; include the client runtime classpath to exercise FilmMatrices too. */
public class AnchorInterpolationTest
{
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception
    {
        AnchorKeyframeFactory factory = new AnchorKeyframeFactory();
        Anchor p = anchor("target", -4F), a = anchor("target", 0F);
        Anchor b = anchor("target", 10F), q = anchor("target", 12F);

        for (IInterp interp : new IInterp[] {Interpolations.AUTO, Interpolations.AUTO_CLAMPED})
        {
            Transform expected = new Transform();
            expected.autoLerp(p.transform, a.transform, b.transform, q.transform, -2, 0, 20, 23, interp == Interpolations.AUTO_CLAMPED, 0.3F);
            Anchor actual = factory.interpolate(key(p, -2), key(a, 0), key(b, 20), key(q, 23), interp, 0.3F);
            near(actual.transform.translate.x, expected.translate.x, "Auto respects uneven key spacing: " + interp.getKey());
        }

        p.replay = "other";
        q.replay = "other";
        p.transform.translate.x = 1000;
        q.transform.translate.x = -1000;
        Anchor isolated = factory.interpolate(p, a, b, q, Interpolations.HERMITE, 0.25F);
        Anchor boundary = factory.interpolate(a, a, b, b, Interpolations.HERMITE, 0.25F);
        near(isolated.transform.translate.x, boundary.transform.translate.x, "Foreign target offsets must not shape local tangents");

        Matrix4f ma = new Matrix4f().translation(2, 3, 4).rotateY(0.4F).scale(2, 3, 4);
        Matrix4f mb = new Matrix4f().translation(8, 9, 10).rotateY(0.4F).scale(4, 5, 6);
        matrix(Matrices.lerp(ma, mb, 0.5F), new Matrix4f().translation(5, 6, 7).rotateY(0.4F).scale(3, 4, 5), "Scaled targets retain rotation and interpolate scale");
        matrix(Matrices.lerp(ma, mb, 0), ma, "Exact start matrix");
        matrix(Matrices.lerp(ma, mb, 1), mb, "Exact end matrix");
        ma.scale(-1, 1, 1);
        mb.scale(-1, 1, 1);
        matrix(Matrices.lerp(ma, mb, 0.5F), new Matrix4f().translation(5, 6, 7).rotateY(0.4F).scale(-3, 4, 5), "Mirrored targets retain their signed scale");
        matrix(Matrices.lerp(ma, mb, 0.5F, ma), new Matrix4f().translation(5, 6, 7).rotateY(0.4F).scale(-3, 4, 5), "Destination can alias an input");
        matrix(Matrices.lerp(new Matrix4f().scaling(0), new Matrix4f().scaling(2), 0.5F), new Matrix4f(), "Zero scale stays finite");

        for (IInterp interp : new IInterp[] {Interpolations.LINEAR, Interpolations.CONST, Interpolations.QUAD_IN})
        {
            float expected = interp.interpolate(0F, 10F, 0.3F);
            near(factory.interpolate(a, a, b, b, interp, 0.3F).transform.translate.x, expected, "Same-target curve: " + interp.getKey());
        }

        if (args.length > 0 && args[0].equals("client"))
        {
            /* Reflection keeps these manual tests compilable in Loom's main-only test source set. */
            var resolve = Class.forName("mchorse.bbs_mod.film.FilmMatrices").getMethod("getTotalMatrix", Map.class, Anchor.class, Matrix4f.class, double.class, double.class, double.class, float.class, int.class);
            a = anchor("", 2);
            b = anchor("", 6);
            b.inheritRotation = false;
            for (float t : new float[] {-0.2F, 0, 0.25F, 0.5F, 1, 1.2F})
            {
                Anchor value = factory.interpolate(a, a, b, b, Interpolations.LINEAR, t);
                Matrix4f fallback = new Matrix4f().translation(10, 0, 0);
                @SuppressWarnings("unchecked")
                Pair<Matrix4f, Float> result = (Pair<Matrix4f, Float>) resolve.invoke(null, Map.of(), value, fallback, 0D, 0D, 0D, 0F, 0);
                Matrix4f actual = result.a == null ? fallback : result.a;
                near(actual.m30(), 12 + 4 * t, "Independent fallback transforms, including overshoot: " + t);
                near(fallback.m30(), 10, "Fallback matrix is unchanged: " + t);
                near(result.b, 1, "Unattached endpoints retain shadow opacity: " + t);
            }

            IEntity target = (IEntity) Proxy.newProxyInstance(IEntity.class.getClassLoader(), new Class<?>[] {IEntity.class}, (proxy, method, values) ->
            {
                if (method.getName().equals("getX") || method.getName().equals("getPrevX")) return 20D;
                if (method.getReturnType() == double.class) return 0D;
                if (method.getReturnType() == float.class) return 0F;
                return null;
            });
            for (String[] targets : new String[][] {{"", "target"}, {"target", ""}, {"target", "other"}, {"missing", "target"}})
            {
                a = anchor(targets[0], 2);
                b = anchor(targets[1], 6);
                for (IInterp interp : new IInterp[] {Interpolations.LINEAR, Interpolations.CONST, Interpolations.QUAD_IN, Interpolations.BACK_IN, Interpolations.BACK_OUT})
                {
                    for (float t : new float[] {0, 0.25F, 0.5F, 0.75F, 1})
                    {
                        Anchor value = factory.interpolate(a, a, b, b, interp, t);
                        boolean fromTarget = a.replay.equals("target") || a.replay.equals("other");
                        boolean toTarget = b.replay.equals("target") || b.replay.equals("other");
                        float weight = interp.interpolate(0F, 1F, t);
                        float fromX = (fromTarget ? 20 : 10) + 2;
                        float toX = (toTarget ? 20 : 10) + 6;
                        Matrix4f fallback = new Matrix4f().translation(10, 0, 0);
                        @SuppressWarnings("unchecked")
                        Pair<Matrix4f, Float> result = (Pair<Matrix4f, Float>) resolve.invoke(null, Map.of("target", target, "other", target), value, fallback, 0D, 0D, 0D, 0F, 0);
                        Matrix4f actual = result.a == null ? fallback : result.a;
                        near(actual.m30(), fromX + (toX - fromX) * weight, "Resolved transition: " + interp.getKey() + " / " + t);
                        near(fallback.m30(), 10, "Resolved transition preserves fallback");
                        float fromOpacity = fromTarget ? 0 : 1;
                        float toOpacity = toTarget ? 0 : 1;
                        near(result.b, Math.max(0, Math.min(1, fromOpacity + (toOpacity - fromOpacity) * weight)), "Shadow fade follows resolved endpoints");
                    }
                }
            }
        }

        System.out.println("Anchor interpolation: " + checks + " checks, " + failures + " failures");
        if (failures > 0) throw new AssertionError("Anchor interpolation regressions: " + failures);
    }

    private static Anchor anchor(String target, float x)
    {
        Anchor result = new Anchor();
        result.replay = target;
        result.transform.translate.x = x;
        return result;
    }

    private static Keyframe<Anchor> key(Anchor value, float tick)
    {
        return new Keyframe<>("", new AnchorKeyframeFactory(), tick, value);
    }

    private static void matrix(Matrix4f actual, Matrix4f expected, String label)
    {
        checks++;
        if (!actual.equals(expected, 0.0001F))
        {
            failures++;
            System.err.println("FAIL " + label + "\nactual:\n" + actual + "expected:\n" + expected);
        }
    }

    private static void near(float actual, float expected, String label)
    {
        checks++;
        if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.0001F)
        {
            failures++;
            System.err.println("FAIL " + label + ": " + actual + " != " + expected);
        }
    }
}
