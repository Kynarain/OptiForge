"""Lets access-transformer grants match by the trailing comment name when the SRG name is unknown.

Forge's cfg writes SRG ids and keeps the readable name in a comment:

    public net.minecraft.client.renderer.RenderPipelines f_379621_ # GUI_TEXTURED_SNIPPET

The bundled 1.21 srg-to-official table has no entry for f_379621_, so the grant could not be translated and the
field stayed private -> ForgeRenderTypes$Internal died with IllegalAccessError on 1.21.8. The comment name is exactly
the runtime name, so it is used as an alternate when the translated name does not match.
"""

from pathlib import Path

path = Path(r"I:\mods\OptiForge\src\main\java\kynarain\cn\optiforge\ForgeAccessTransformers.java")
text = path.read_text(encoding="utf-8")
changed = []

# 1. Grant gains an alternate name
if "String alternate" not in text:
    text = text.replace(
        "    private static final class Grant {",
        "    private static final class Grant {\n\n        /** Name from the trailing comment; used when the SRG id cannot be translated. */\n        String alternate = \"\";\n",
        1,
    )
    changed.append("field")

# 2. capture the comment before stripping it
old_comment = """            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            if (line.isEmpty()) continue;"""
new_comment = """            String commentName = "";
            int comment = line.indexOf('#');
            if (comment >= 0) {
                String tail = line.substring(comment + 1).trim();
                int space = tail.indexOf(' ');
                commentName = space > 0 ? tail.substring(0, space) : tail;
                line = line.substring(0, comment).trim();
            }
            if (line.isEmpty()) continue;"""
if old_comment in text:
    text = text.replace(old_comment, new_comment, 1)
    changed.append("comment")

# 3. store the alternate on the grant
old_grant = """            String descriptorFinal = descriptor;
            grants.computeIfAbsent(className, k -> new ArrayList<>())
                    .add(new Grant(member, descriptorFinal, access, clearFinal));
            count++;"""
new_grant = """            String descriptorFinal = descriptor;
            Grant grant = new Grant(member, descriptorFinal, access, clearFinal);
            grant.alternate = commentName;
            grants.computeIfAbsent(className, k -> new ArrayList<>()).add(grant);
            count++;"""
if old_grant in text:
    text = text.replace(old_grant, new_grant, 1)
    changed.append("grant")

# 4. match on either name
text = text.replace(
    "                if (!grant.wildcard && !field.name.equals(grant.member)) continue;",
    "                if (!grant.wildcard && !field.name.equals(grant.member) && !field.name.equals(grant.alternate)) continue;",
    1,
)
text = text.replace(
    "                if (!grant.wildcard && !method.name.equals(grant.member)) continue;",
    "                if (!grant.wildcard && !method.name.equals(grant.member) && !method.name.equals(grant.alternate)) continue;",
    1,
)
changed.append("match")

path.write_text(text, encoding="utf-8")
print("changed:", ", ".join(changed))
