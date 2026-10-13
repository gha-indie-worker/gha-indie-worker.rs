package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.TaggedMessageCodec;
import dev.oreslang.types.TaggedMessageContracts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Nominal enums cannot become untyped integer tags through alternate AST entrypoints. */
final class EnumNominalIdentityBoundaryTest {
    private static Ast.Program program(Ast.Decl... decls) {
        return new Ast.Program(List.of(new Ast.ModuleDecl(
                Parser.ROOT_MODULE, List.of(decls))));
    }

    private static Ast.EnumCase c(String name, int tag) {
        return new Ast.EnumCase(name, null, tag);
    }

    @Test
    void registryRejectsMalformedInternalAstRatherThanTrustingOnlyTextParser() {
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(
                        new Ast.EnumDecl("E", List.of(c("A", 1), c("A", 2))))));
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(
                        new Ast.EnumDecl("E", List.of(c("A", 2), c("B", 1))))));
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(
                        new Ast.EnumDecl("E", List.of(c("A", 1), c("B", 1))))));
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(
                        new Ast.EnumDecl("E", List.of(c("A", 0))))));
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(
                        new Ast.EnumDecl("E", List.of()))));
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(
                        new Ast.EnumDecl("E", List.of(c("A", 1))),
                        new Ast.PatternDecl("P", List.of("E.A", "E.A")))));
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(
                        new Ast.EnumDecl("E", List.of(c("A", 1))),
                        new Ast.PatternDecl("P", List.of()))));
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(
                        new Ast.EnumDecl("E", List.of(c("A", 1))),
                        new Ast.PatternDecl("P", List.of("E.A", "E.B")))));
    }

    @Test
    void registryCapsCaseAndPatternCardinalityForInternalAsts() {
        List<Ast.EnumCase> tooManyCases = new ArrayList<>();
        for (int n = 1; n <= 4097; n++) tooManyCases.add(c("C" + n, n));
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(new Ast.EnumDecl("E", tooManyCases))));
        List<String> tooManyAlternatives = new ArrayList<>();
        for (int n = 0; n < 4097; n++) tooManyAlternatives.add("E.A");
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(program(
                        new Ast.EnumDecl("E", List.of(c("A", 1))),
                        new Ast.PatternDecl("P", tooManyAlternatives))));
    }

    @Test
    void hostTaggedValuesAreNominalNotIntegersOrPayloadAliases() {
        var first = new TaggedMessageCodec.TaggedValue(
                "Status", "Message1", 1, List.of(true, "foo"));
        var sameFirst = new TaggedMessageCodec.TaggedValue(
                "Status", "Message1", 1, List.of(true, "foo"));
        var second = new TaggedMessageCodec.TaggedValue(
                "Status", "Message2", 2, "same");
        var third = new TaggedMessageCodec.TaggedValue(
                "Status", "Message3", 3, "same");
        var otherEnum = new TaggedMessageCodec.TaggedValue(
                "Other", "Message2", 2, "same");
        assertEquals(first, sameFirst);
        assertEquals(first.hashCode(), sameFirst.hashCode());
        assertNotEquals(second, third);
        assertNotEquals(second, otherEnum);
        assertNotEquals(second, 2);

        // This tests the Java host representation, not yet guest HashMap<T,T>
        // lowering: guest key support must enforce immutable, hashable payloads.
        var map = new HashMap<TaggedMessageCodec.TaggedValue,
                TaggedMessageCodec.TaggedValue>();
        map.put(first, second);
        assertEquals(second, map.get(sameFirst));
        assertNull(map.get(new TaggedMessageCodec.TaggedValue(
                "Status", "Message1", 1, List.of(false, "foo"))));
    }

    @Test
    void alternateStringCodeSyntaxDoesNotSilentlyBecomeNumericTagOrPayload() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum Status as
                    Message1: Tuple[bool, string] = "one"
                end
                """));
    }
    @Test
    void codecRejectsForgedDomainsSharingWireTagOrErasingPayloadType() {
        var stringType = Ast.TypeRef.simple("string");
        var payloadShape = dev.oreslang.runtime.MessagePackCollectionCodec.string();
        var a = new TaggedMessageContracts.Case(
                "Status", "Message2", 2, stringType, payloadShape);
        var b = new TaggedMessageContracts.Case(
                "Status", "Message3", 2, stringType, payloadShape);
        assertThrows(IllegalArgumentException.class, () ->
                new TaggedMessageCodec(new TaggedMessageContracts.Domain(
                        "Messages", List.of(a, b))));
        var erased = new TaggedMessageContracts.Case(
                "Status", "Message2", 2, stringType, null);
        assertThrows(IllegalArgumentException.class, () ->
                new TaggedMessageCodec(new TaggedMessageContracts.Domain(
                        "Messages", List.of(erased))));
    }

}
