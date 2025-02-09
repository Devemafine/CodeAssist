package com.tyron.completion.xml.providers;

import static com.tyron.completion.xml.util.XmlUtils.fullIdentifier;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeItem;
import static com.tyron.completion.xml.util.XmlUtils.getAttributeNameFromPrefix;
import static com.tyron.completion.xml.util.XmlUtils.getElementNode;
import static com.tyron.completion.xml.util.XmlUtils.getTagAtPosition;
import static com.tyron.completion.xml.util.XmlUtils.isInAttribute;
import static com.tyron.completion.xml.util.XmlUtils.isInAttributeValue;
import static com.tyron.completion.xml.util.XmlUtils.isIncrementalCompletion;
import static com.tyron.completion.xml.util.XmlUtils.isTag;
import static com.tyron.completion.xml.util.XmlUtils.newPullParser;
import static com.tyron.completion.xml.util.XmlUtils.partialIdentifier;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Pair;

import androidx.annotation.RequiresApi;

import com.tyron.builder.compiler.manifest.blame.SourcePosition;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.util.PositionXmlParser;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.CompletionProvider;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.XmlIndexProvider;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.insert.AttributeInsertHandler;
import com.tyron.completion.xml.insert.LayoutTagInsertHandler;
import com.tyron.completion.xml.insert.ValueInsertHandler;
import com.tyron.completion.xml.lexer.XMLLexer;
import com.tyron.completion.xml.model.AttributeInfo;
import com.tyron.completion.xml.model.DeclareStyleable;
import com.tyron.completion.xml.model.XmlCachedCompletion;
import com.tyron.completion.xml.util.AndroidResourcesUtils;
import com.tyron.completion.xml.util.StyleUtils;
import com.tyron.completion.xml.util.XmlUtils;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.apache.bcel.classfile.JavaClass;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMParser;
import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.xdrop.fuzzywuzzy.FuzzySearch;

@SuppressLint("NewApi")
public class LayoutXmlCompletionProvider extends CompletionProvider {

    private static final String EXTENSION = ".xml";

    private XmlCachedCompletion mCachedCompletion;

    public LayoutXmlCompletionProvider() {

    }

    @Override
    public boolean accept(File file) {
        return file.isFile() && AndroidResourcesUtils.isLayoutXMLFile(file);
    }

    @Override
    public CompletionList complete(CompletionParameters params) {

        if (!(params.getModule() instanceof AndroidModule)) {
            return CompletionList.EMPTY;
        }

        String contents = params.getContents();
        String prefix = params.getPrefix();
        String partialIdentifier = partialIdentifier(contents, (int) params.getIndex());

        if (isIncrementalCompletion(mCachedCompletion, params)) {
            if (mCachedCompletion.getCompletionType() == XmlCachedCompletion.TYPE_ATTRIBUTE_VALUE) {
                mCachedCompletion.setFilterPrefix(prefix);
            } else {
                mCachedCompletion.setFilterPrefix(partialIdentifier);
            }
            CompletionList completionList = mCachedCompletion.getCompletionList();
            if (!completionList.items.isEmpty()) {
                String filterPrefix = mCachedCompletion.getFilterPrefix();
                sort(completionList.items, filterPrefix);
                return completionList;
            }
        }
        try {
            XmlCachedCompletion list =
                    completeInternal(params.getProject(),
                            (AndroidModule) params.getModule(),
                            params.getFile(),
                            contents,
                            prefix,
                            params.getLine(),
                            params.getColumn(),
                            params.getIndex());
            mCachedCompletion = list;
            CompletionList completionList = list.getCompletionList();
            sort(completionList.items, list.getFilterPrefix());
            return completionList;
        } catch (XmlPullParserException | IOException | ParserConfigurationException | SAXException e) {
            e.printStackTrace();
        }

        return CompletionList.EMPTY;
    }

    private void sort(List<CompletionItem> items, String filterPrefix) {
        items.sort(Comparator.comparingInt(it -> {
            if (it.label.equals(filterPrefix)) {
                return 100;
            }
            return FuzzySearch.ratio(it.label, filterPrefix);
        }));
        Collections.reverse(items);
    }


    @RequiresApi(api = Build.VERSION_CODES.N)
    private XmlCachedCompletion completeInternal(Project project, AndroidModule module, File file, String contents,
                                                 String prefix, int line, int column, long index) throws XmlPullParserException, IOException, ParserConfigurationException, SAXException {
        CompletionList list = new CompletionList();
        XmlCachedCompletion xmlCachedCompletion = new XmlCachedCompletion(file,
                line, column, prefix, list);

        XmlIndexProvider indexProvider =
                CompilerService.getInstance().getIndex(XmlIndexProvider.KEY);
        XmlRepository repository = indexProvider.get(project, module);
        repository.initialize(module);
        Map<String, DeclareStyleable> declareStyleables = repository.getDeclareStyleables();

        String fixedPrefix = partialIdentifier(contents, (int) index);
        String fullPrefix = fullIdentifier(contents, (int) index);

        DOMDocument parsed = DOMParser.getInstance().parse(contents, "", null);
        DOMNode node = parsed.findNodeAt((int) index);

        String parentTag = "";
        String tag = "";
        Element ownerNode = getElementNode(node);
        if (ownerNode != null) {
            parentTag = ownerNode.getParentNode() == null
                    ? ""
                    : ownerNode.getParentNode().getNodeName();
            tag = ownerNode.getTagName();
        }

        // first get the attributes based on the current tag
        Set<DeclareStyleable> styles = StyleUtils.getStyles(declareStyleables, tag, parentTag);
        list.items = new ArrayList<>();

        if (isTag(node, index)) {
            addTagItems(repository, prefix, list, xmlCachedCompletion);
        } if (isInAttributeValue(contents, (int) index)) {
            addAttributeValueItems(styles, prefix, fixedPrefix, repository, list, xmlCachedCompletion);
        } else {
            addAttributeItems(styles, fullPrefix, fixedPrefix, repository, list, xmlCachedCompletion);
        }


        return xmlCachedCompletion;
    }

    private void addTagItems(XmlRepository repository, String prefix, CompletionList list, XmlCachedCompletion xmlCachedCompletion) {
        xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_TAG);
        xmlCachedCompletion.setFilterPrefix(prefix);
        xmlCachedCompletion.setFilter((item, pre) -> {
            String prefixSet = pre;

            if (pre.startsWith("</")) {
                prefixSet = pre.substring(2);
            } else if (pre.startsWith("<")) {
                prefixSet = pre.substring(1);
            }

            if (prefixSet.contains(".")) {
                if (FuzzySearch.partialRatio(prefixSet, item.detail) >= 80) {
                    return true;
                }
            } else {
                if (FuzzySearch.partialRatio(prefixSet, item.label) >= 80) {
                    return true;
                }
            }

            String className = item.detail + "." + item.label;
            return FuzzySearch.partialRatio(prefixSet, className) >= 30;

        });
        for (Map.Entry<String, JavaClass> entry :
                repository.getJavaViewClasses().entrySet()) {
            CompletionItem item = new CompletionItem();
            String commitPrefix = "<";
            if (prefix.startsWith("</")) {
                commitPrefix = "</";
            }
            boolean useFqn = prefix.contains(".");
            if (!entry.getKey().startsWith("android.widget")) {
                useFqn = true;
            }
            item.label = StyleUtils.getSimpleName(entry.getKey());
            item.detail = entry.getValue().getPackageName();
            item.iconKind = DrawableKind.Class;
            item.commitText = commitPrefix +
                    (useFqn
                            ? entry.getValue().getClassName()
                            : StyleUtils.getSimpleName(entry.getValue().getClassName()));
            item.cursorOffset = item.commitText.length();
            item.setInsertHandler(new LayoutTagInsertHandler(entry.getValue(), item));
            list.items.add(item);
        }
    }

    private void addAttributeItems(Set<DeclareStyleable> styles, String fullPrefix, String fixedPrefix, XmlRepository repository, CompletionList list, XmlCachedCompletion xmlCachedCompletion) {
        boolean shouldShowNamespace = !fixedPrefix.contains(":");

        for (DeclareStyleable style : styles) {
            for (AttributeInfo attributeInfo : style.getAttributeInfos()) {
                if (attributeInfo.getFormats() == null || attributeInfo.getFormats().isEmpty()) {
                    AttributeInfo extra = repository.getExtraAttribute(attributeInfo.getName());
                    if (extra != null) {
                        attributeInfo = extra;
                    }
                }
                CompletionItem item = getAttributeItem(repository, attributeInfo, shouldShowNamespace, fullPrefix);
                item.setInsertHandler(new AttributeInsertHandler(item));
                list.items.add(item);
            }
        }
        xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_ATTRIBUTE);
        xmlCachedCompletion.setFilterPrefix(fixedPrefix);
        xmlCachedCompletion.setFilter((it, pre) -> {
            if (pre.contains(":")) {
                if (pre.endsWith(":")) {
                    return true;
                }
                if (it.label.contains(":")) {
                    if (!it.label.startsWith(pre)) {
                        return false;
                    }
                    it.label = it.label.substring(it.label.indexOf(':') + 1);
                }
            }
            if (it.label.startsWith(pre)) {
                return true;
            }

            String labelPrefix = getAttributeNameFromPrefix(it.label);
            String prePrefix = getAttributeNameFromPrefix(pre);
            return FuzzySearch.partialRatio(labelPrefix, prePrefix) >= 70;
        });
    }

    private void addAttributeValueItems(Set<DeclareStyleable> styles, String prefix, String fixedPrefix, XmlRepository repository, CompletionList list, XmlCachedCompletion xmlCachedCompletion) {
        for (DeclareStyleable style : styles) {
            for (AttributeInfo attributeInfo : style.getAttributeInfos()) {
                String attributeName = getAttributeNameFromPrefix(fixedPrefix);
                String namespace = "";
                if (fixedPrefix.contains(":")) {
                    namespace = fixedPrefix.substring(0, fixedPrefix.indexOf(':'));
                    if (namespace.contains("=")) {
                        namespace = namespace.substring(0, namespace.indexOf('='));
                    }
                }
                if (!namespace.equals(attributeInfo.getNamespace())) {
                    continue;
                }
                if (!attributeName.isEmpty()) {
                    if (!attributeName.equals(attributeInfo.getName())) {
                        continue;
                    }
                }

                List<String> values = attributeInfo.getValues();
                if (values == null || values.isEmpty()) {
                    AttributeInfo extraAttribute =
                            repository.getExtraAttribute(attributeInfo.getName());
                    if (extraAttribute != null) {
                        values = extraAttribute.getValues();
                    }
                }
                if (values != null) {
                    for (String value : values) {
                        CompletionItem item = new CompletionItem();
                        item.action = CompletionItem.Kind.NORMAL;
                        item.label = value;
                        item.commitText = value;
                        item.iconKind = DrawableKind.Attribute;
                        item.cursorOffset = value.length();
                        item.detail = "Attribute";
                        item.setInsertHandler(new ValueInsertHandler(attributeInfo, item));
                        list.items.add(item);
                    }
                }
            }
        }
        xmlCachedCompletion.setCompletionType(XmlCachedCompletion.TYPE_ATTRIBUTE_VALUE);
        xmlCachedCompletion.setFilterPrefix(prefix);
        xmlCachedCompletion.setFilter((item, pre) -> item.label.startsWith(pre));
    }
}
